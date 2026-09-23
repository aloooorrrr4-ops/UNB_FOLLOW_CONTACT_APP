package com.unb.imageeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfessionalEditorActivity extends Activity {

    private static final int PICK_IMAGE = 4101;
    private static final int CREATE_EXPORT = 4102;

    private final int BG = Color.rgb(20, 22, 26);
    private final int PANEL = Color.rgb(28, 31, 36);
    private final int PANEL_2 = Color.rgb(35, 39, 45);
    private final int LINE = Color.rgb(55, 60, 68);
    private final int TEXT = Color.rgb(239, 242, 246);
    private final int MUTED = Color.rgb(159, 167, 178);
    private final int ACCENT = Color.rgb(61, 139, 255);

    private EditorCanvasView canvas;
    private final LocalEditorEngine localEngine = new LocalEditorEngine();
    private final ExecutorService localExecutor = Executors.newSingleThreadExecutor();
    private String projectId;
    private byte[] sourceBytes;
    private String sourceName = "image.png";

    private TextView statusText;
    private TextView zoomText;
    private TextView projectText;
    private TextView toolTitle;
    private LinearLayout toolOptions;
    private LinearLayout layerList;
    private LinearLayout channelList;
    private LinearLayout pathList;
    private LinearLayout resourceList;
    private LinearLayout historyList;
    private Button activeToolButton;
    private String activeTool = "move";
    private boolean desktopLayout = false;
    private View phoneInspectorPanel;
    private ScrollView inspectorBodyScroll;
    private HorizontalScrollView phoneContextScroll;
    private boolean phoneInspectorExpanded = false;
    private float lastImageTapX = 0f;
    private float lastImageTapY = 0f;
    private int brushSize = 45;
    private int brushOpacity = 100;
    private String foregroundColor = "#ffffff";
    private float cloneSourceX = Float.NaN;
    private float cloneSourceY = Float.NaN;
    private float cropAspectRatio = 0f;
    private byte[] pendingExportBytes;
    private String pendingExportName = "export.png";

    private final List<String> history = new ArrayList<>();
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(14, 16, 19));
        getWindow().setNavigationBarColor(Color.rgb(14, 16, 19));

        desktopLayout = isDesktopLayout();

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Math.max(0, insets.getSystemWindowInsetTop());
            int bottom = Math.max(0, insets.getSystemWindowInsetBottom());
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        root.addView(buildMenuBar());
        root.addView(divider());
        root.addView(buildQuickBar());
        root.addView(divider());

        View workspace = desktopLayout ? buildDesktopWorkspace() : buildPhoneWorkspace();
        root.addView(workspace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(divider());
        root.addView(buildStatusBar());

        setContentView(root);
        showTool("move", "تحريك", null);
        addHistory("جاهز");
        refreshLayers();
        pingServer();
    }

    private View buildMenuBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.rgb(16, 18, 21));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(desktopLayout ? 6 : 2), dp(desktopLayout ? 4 : 2),
                dp(desktopLayout ? 6 : 2), dp(desktopLayout ? 4 : 2));
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        String[] menus = {"ملف", "تعديل", "تحديد", "عرض", "صورة", "طبقة", "ألوان", "أدوات", "فلاتر", "نوافذ"};
        for (String name : menus) {
            Button b = flatButton(name, desktopLayout ? 13 : 12);
            b.setOnClickListener(v -> showMenuGroup(name));
            row.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(desktopLayout ? 38 : 34)));
        }

        scroller.addView(row);
        return scroller;
    }

    private View buildQuickBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(PANEL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(desktopLayout ? 6 : 3), dp(desktopLayout ? 5 : 3),
                dp(desktopLayout ? 6 : 3), dp(desktopLayout ? 5 : 3));
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        row.addView(actionButton("فتح", v -> chooseImage()));
        row.addView(actionButton("حفظ", v -> exportProject("png")));
        row.addView(actionButton("محلي", v -> showServerDialog()));
        row.addView(verticalDivider());
        row.addView(actionButton("↶ تراجع", v -> remoteHistory("undo")));
        row.addView(actionButton("↷ إعادة", v -> remoteHistory("redo")));
        row.addView(verticalDivider());
        row.addView(actionButton("↺ 90°", v -> applyRemote("rotate", json("degrees", -90))));
        row.addView(actionButton("↻ 90°", v -> applyRemote("rotate", json("degrees", 90))));
        row.addView(actionButton("↔ عكس", v -> applyRemote("flip_horizontal", new JSONObject())));
        row.addView(actionButton("↕ عكس", v -> applyRemote("flip_vertical", new JSONObject())));
        row.addView(verticalDivider());
        row.addView(actionButton("ملاءمة", v -> canvas.fitToView()));
        row.addView(actionButton("100%", v -> canvas.setZoom(1f)));

        scroller.addView(row);
        return scroller;
    }

    private View buildDesktopWorkspace() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(BG);

        row.addView(buildToolRail(), new LinearLayout.LayoutParams(dp(86),
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(dividerVertical());

        canvas = createCanvas();
        row.addView(canvas, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        row.addView(dividerVertical());
        row.addView(buildInspector(), new LinearLayout.LayoutParams(dp(310),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private View buildPhoneWorkspace() {
        LinearLayout root = column();

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);

        canvas = createCanvas();
        middle.addView(canvas, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        middle.addView(dividerVertical());
        middle.addView(buildToolRail(), new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(middle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(divider());
        phoneInspectorPanel = buildPhoneContextPanel();
        root.addView(phoneInspectorPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(104)));
        return root;
    }

    private EditorCanvasView createCanvas() {
        EditorCanvasView v = new EditorCanvasView(this);
        v.setListener(new EditorCanvasView.Listener() {
            @Override
            public void onViewportChanged(float zoom, float imageX, float imageY) {
                if (zoomText != null) zoomText.setText(Math.round(zoom * 100f) + "%");
                if (statusText != null) {
                    statusText.setText(activeToolLabel() + "  •  X " +
                            Math.round(imageX) + "  Y " + Math.round(imageY));
                }
            }

            @Override
            public void onTapImage(float imageX, float imageY) {
                lastImageTapX = imageX;
                lastImageTapY = imageY;
                if ("color_picker".equals(activeTool)) {
                    pickLocalColor(imageX, imageY);
                } else if ("fill".equals(activeTool) && projectId != null) {
                    applyRemote("fill", jsonOf("color", foregroundColor));
                } else if ("fuzzy_select".equals(activeTool) && projectId != null) {
                    applyRemote("select_contiguous", jsonOf(
                            "x", imageX, "y", imageY, "threshold", 0.15));
                } else if ("color_select".equals(activeTool) && projectId != null) {
                    applyRemote("select_color", jsonOf(
                            "color", foregroundColor, "threshold", 0.15));
                } else if ("text".equals(activeTool) && projectId != null) {
                    showAddTextDialog(imageX, imageY);
                }
            }

            @Override
            public void onStrokeCompleted(float[] imagePoints) {
                handleCanvasStroke(imagePoints);
            }
        });
        return v;
    }

    private View buildToolRail() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PANEL);

        LinearLayout rail = column();
        int railPad = desktopLayout ? 6 : 4;
        rail.setPadding(dp(railPad), dp(desktopLayout ? 7 : 5),
                dp(railPad), dp(desktopLayout ? 8 : 5));

        addTool(rail, "move", "تحريك");
        addTool(rail, "select", "تحديد");
        addTool(rail, "free_select", "لاسو");
        addTool(rail, "fuzzy_select", "سحري");
        addTool(rail, "color_select", "حسب لون");
        addTool(rail, "crop", "قص");
        addTool(rail, "transform", "تحويل");
        addTool(rail, "perspective", "منظور");
        addTool(rail, "brush", "فرشاة");
        addTool(rail, "pencil", "قلم");
        addTool(rail, "eraser", "ممحاة");
        addTool(rail, "fill", "تعبئة");
        addTool(rail, "gradient", "تدرج");
        addTool(rail, "text", "نص");
        addTool(rail, "clone", "استنساخ");
        addTool(rail, "heal", "ترميم");
        addTool(rail, "smudge", "تلطيخ");
        addTool(rail, "dodge_burn", "إضاءة");
        addTool(rail, "color_picker", "لون");
        addTool(rail, "zoom", "تكبير");

        scroll.addView(rail);
        return scroll;
    }

    private void addTool(LinearLayout rail, String id, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(desktopLayout ? 11 : 10);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(3), dp(2), dp(3));
        b.setBackground(rounded(PANEL_2, 9));
        b.setTag(id);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(desktopLayout ? 48 : 42));
        lp.bottomMargin = dp(desktopLayout ? 5 : 4);
        rail.addView(b, lp);

        b.setOnClickListener(v -> {
            if (activeToolButton != null) {
                activeToolButton.setBackground(rounded(PANEL_2, 9));
            }
            activeToolButton = b;
            b.setBackground(rounded(ACCENT, 9));
            activeTool = id;
            showTool(id, label, b);
        });

        if ("move".equals(id)) activeToolButton = b;
    }

    private View buildInspector() {
        LinearLayout panel = column();
        panel.setBackgroundColor(PANEL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(desktopLayout ? 5 : 2), dp(desktopLayout ? 5 : 3),
                dp(desktopLayout ? 5 : 2), dp(2));

        Button props = tabButton("خصائص");
        Button layers = tabButton("طبقات");
        Button channels = tabButton("قنوات");
        Button paths = tabButton("مسارات");
        Button resources = tabButton("موارد");
        Button hist = tabButton("سجل");
        Button collapse = tabButton("⌄");

        int tabHeight = desktopLayout ? 40 : 36;
        Button[] mainTabs = {props, layers, channels, paths, resources, hist};

        if (desktopLayout) {
            HorizontalScrollView tabScroll = new HorizontalScrollView(this);
            tabScroll.setHorizontalScrollBarEnabled(false);
            for (Button tab : mainTabs) {
                tabs.addView(tab, new LinearLayout.LayoutParams(dp(72), dp(tabHeight)));
            }
            tabScroll.addView(tabs);
            panel.addView(tabScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(tabHeight + 8)));
        } else {
            for (Button tab : mainTabs) {
                tab.setTextSize(9.5f);
                tab.setPadding(dp(2), 0, dp(2), 0);
                tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(tabHeight), 1f));
            }
            collapse.setTextSize(12);
            collapse.setPadding(0, 0, 0, 0);
            tabs.addView(collapse, new LinearLayout.LayoutParams(dp(32), dp(tabHeight)));
            panel.addView(tabs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(tabHeight + 8)));
        }

        ScrollView bodyScroll = new ScrollView(this);
        inspectorBodyScroll = bodyScroll;
        LinearLayout body = column();
        body.setPadding(dp(12), dp(8), dp(12), dp(12));
        bodyScroll.addView(body);
        panel.addView(bodyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        toolOptions = column();
        layerList = column();
        channelList = column();
        pathList = column();
        resourceList = column();
        historyList = column();

        body.addView(toolOptions);
        body.addView(layerList);
        body.addView(channelList);
        body.addView(pathList);
        body.addView(resourceList);
        body.addView(historyList);

        props.setOnClickListener(v -> openInspectorTab("properties"));
        layers.setOnClickListener(v -> {
            openInspectorTab("layers");
            refreshLayers();
        });
        channels.setOnClickListener(v -> {
            openInspectorTab("channels");
            refreshChannels();
        });
        paths.setOnClickListener(v -> {
            openInspectorTab("paths");
            refreshPaths();
        });
        resources.setOnClickListener(v -> {
            openInspectorTab("resources");
            loadResources("fonts");
        });
        hist.setOnClickListener(v -> openInspectorTab("history"));
        collapse.setOnClickListener(v -> setPhoneInspectorExpanded(false));

        showInspector("properties");
        if (!desktopLayout) bodyScroll.setVisibility(View.GONE);
        return panel;
    }

    private void openInspectorTab(String tab) {
        showInspector(tab);
        setPhoneInspectorExpanded(true);
    }

    private void setPhoneInspectorExpanded(boolean expanded) {
        if (desktopLayout || phoneInspectorPanel == null) return;
        phoneInspectorExpanded = expanded;

        ViewGroup.LayoutParams raw = phoneInspectorPanel.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.height = dp(expanded ? 188 : 48);
            phoneInspectorPanel.setLayoutParams(lp);
        }

        if (inspectorBodyScroll != null) {
            inspectorBodyScroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    private void showInspector(String tab) {
        if (toolOptions == null) return;
        toolOptions.setVisibility("properties".equals(tab) ? View.VISIBLE : View.GONE);
        layerList.setVisibility("layers".equals(tab) ? View.VISIBLE : View.GONE);
        channelList.setVisibility("channels".equals(tab) ? View.VISIBLE : View.GONE);
        pathList.setVisibility("paths".equals(tab) ? View.VISIBLE : View.GONE);
        resourceList.setVisibility("resources".equals(tab) ? View.VISIBLE : View.GONE);
        historyList.setVisibility("history".equals(tab) ? View.VISIBLE : View.GONE);
    }

    private void showTool(String id, String label, Button source) {
        if (source != null && activeToolButton != source) {
            if (activeToolButton != null) activeToolButton.setBackground(rounded(PANEL_2, 9));
            activeToolButton = source;
            source.setBackground(rounded(ACCENT, 9));
        }
        activeTool = id;
        if (canvas != null) {
            canvas.setInteractionMode(id);
            canvas.setStrokePreview(brushSize, parseColorSafe(foregroundColor));
        }
        if (toolOptions == null) return;

        toolOptions.removeAllViews();
        toolTitle = label("أداة: " + label, 17, TEXT, true);
        toolOptions.addView(toolTitle);

        if (Arrays.asList("brush", "pencil", "eraser", "clone", "heal", "smudge", "dodge_burn").contains(id)) {
            addSlider(toolOptions, "الحجم", 1, 300, brushSize, value -> {
                brushSize = value;
                if (canvas != null) canvas.setStrokePreview(brushSize, parseColorSafe(foregroundColor));
            });
            addSlider(toolOptions, "العتامة", 0, 100, brushOpacity, value -> brushOpacity = value);
            addSlider(toolOptions, "الصلابة", 0, 100, 70, null);
            addSlider(toolOptions, "التباعد", 1, 200, 20, null);
            if ("clone".equals(id) || "heal".equals(id)) {
                toolOptions.addView(actionButton("إعادة تحديد المصدر", v -> {
                    cloneSourceX = Float.NaN;
                    cloneSourceY = Float.NaN;
                    setStatus("اسحب/المس نقطة المصدر أولاً ثم ارسم في المكان المطلوب");
                }));
            }
        } else if ("text".equals(id)) {
            addSlider(toolOptions, "حجم الخط", 6, 300, 42, null);
            addSlider(toolOptions, "تباعد الحروف", 0, 100, 0, null);
            addSlider(toolOptions, "تباعد الأسطر", 0, 150, 20, null);
            toolOptions.addView(actionButton("+ إضافة نص", v ->
                    showAddTextDialog(lastImageTapX, lastImageTapY)));
            toolOptions.addView(actionButton("الخطوط", v -> {
                openInspectorTab("resources");
                loadResources("fonts");
            }));
            toolOptions.addView(actionButton("RTL / LTR", v -> toast("اتجاه النص ضمن الدفعة القادمة")));
        } else if ("crop".equals(id)) {
            toolOptions.addView(label("اسحب مستطيلاً على الصورة؛ عند رفع إصبعك يتم القص.", 13, MUTED, false));
        } else if ("transform".equals(id) || "perspective".equals(id)) {
            toolOptions.addView(actionButton("خيارات التحويل", v ->
                    toast("التحويل الحر المتقدم ضمن لوحة التحويل القادمة")));
            toolOptions.addView(actionButton("إلغاء", v -> setStatus("تم إلغاء التحويل")));
        } else if ("gradient".equals(id) || "fill".equals(id)) {
            addSlider(toolOptions, "العتامة", 0, 100, 100, null);
            toolOptions.addView(actionButton("لون المقدمة", v -> toast("منتقي الألوان")));
            toolOptions.addView(actionButton("لون الخلفية", v -> toast("منتقي الألوان")));
        } else {
            TextView help = label(toolHelp(id), 13, MUTED, false);
            help.setPadding(0, dp(10), 0, dp(6));
            toolOptions.addView(help);
        }

        toolOptions.addView(divider());
        toolOptions.addView(label("ضبط سريع للصورة", 14, TEXT, true));

        addSlider(toolOptions, "السطوع", -100, 100, 0,
                value -> applyRemote("brightness", json("value", value)));
        addSlider(toolOptions, "التباين", -100, 100, 0,
                value -> applyRemote("contrast", json("value", value)));
        addSlider(toolOptions, "التشبع", -100, 100, 0,
                value -> applyRemote("saturation", json("value", value)));
    }

    private void handleCanvasStroke(float[] points) {
        if (canvas == null || points == null || points.length < 2) return;

        if (projectId == null) {
            canvas.clearStrokePreview();
            toast("افتح صورة أولاً");
            return;
        }

        if (busy) {
            canvas.clearStrokePreview();
            setStatus("انتظر انتهاء العملية الحالية");
            return;
        }

        lastImageTapX = points[points.length - 2];
        lastImageTapY = points[points.length - 1];

        if ("select".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            float x0 = points[0];
            float y0 = points[1];
            float x1 = points[points.length - 2];
            float y1 = points[points.length - 1];
            int x = Math.round(Math.min(x0, x1));
            int y = Math.round(Math.min(y0, y1));
            int w = Math.max(1, Math.round(Math.abs(x1 - x0)));
            int h = Math.max(1, Math.round(Math.abs(y1 - y0)));
            applyRemote("select_rectangle", jsonOf(
                    "x", x, "y", y, "width", w, "height", h));
            return;
        }

        if ("crop".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            float x0 = points[0];
            float y0 = points[1];
            float x1 = points[points.length - 2];
            float y1 = points[points.length - 1];
            int x = Math.round(Math.min(x0, x1));
            int y = Math.round(Math.min(y0, y1));
            int w = Math.max(1, Math.round(Math.abs(x1 - x0)));
            int h = Math.max(1, Math.round(Math.abs(y1 - y0)));
            applyRemote("crop", jsonOf("x", x, "y", y, "width", w, "height", h));
            return;
        }

        if ("free_select".equals(activeTool)) {
            if (points.length < 6) {
                canvas.clearStrokePreview();
                setStatus("ارسم ثلاث نقاط على الأقل للتحديد الحر");
                return;
            }
            applyRemote("select_polygon", jsonOf("points", pointsJson(points)));
            return;
        }

        if ("gradient".equals(activeTool)) {
            if (points.length < 4) {
                canvas.clearStrokePreview();
                return;
            }
            applyRemote("gradient", jsonOf(
                    "x1", points[0],
                    "y1", points[1],
                    "x2", points[points.length - 2],
                    "y2", points[points.length - 1],
                    "type", "linear",
                    "foreground", foregroundColor,
                    "background", "#000000"
            ));
            return;
        }

        if ("clone".equals(activeTool) || "heal".equals(activeTool)) {
            if (Float.isNaN(cloneSourceX) || Float.isNaN(cloneSourceY)) {
                cloneSourceX = points[0];
                cloneSourceY = points[1];
                canvas.clearStrokePreview();
                setStatus("تم تحديد المصدر عند X " + Math.round(cloneSourceX) +
                        " Y " + Math.round(cloneSourceY) + " — ارسم الآن على الهدف");
                return;
            }

            applyRemote(activeTool, jsonOf(
                    "source_x", cloneSourceX,
                    "source_y", cloneSourceY,
                    "points", pointsJson(points),
                    "size", brushSize,
                    "opacity", brushOpacity
            ));
            return;
        }

        if ("brush".equals(activeTool)) {
            applyRemote("paintbrush", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "opacity", brushOpacity,
                    "color", foregroundColor
            ));
            return;
        }

        if ("pencil".equals(activeTool) || "eraser".equals(activeTool)) {
            applyRemote(activeTool, jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "opacity", brushOpacity,
                    "color", foregroundColor
            ));
            return;
        }

        if ("smudge".equals(activeTool)) {
            applyRemote("smudge", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "pressure", brushOpacity
            ));
            return;
        }

        if ("dodge_burn".equals(activeTool)) {
            applyRemote("dodge_burn", jsonOf(
                    "points", pointsJson(points),
                    "size", brushSize,
                    "exposure", brushOpacity,
                    "type", "dodge",
                    "range", "midtones"
            ));
            return;
        }

        canvas.clearStrokePreview();
    }

    private JSONArray pointsJson(float[] points) {
        JSONArray array = new JSONArray();
        if (points != null) {
            for (float point : points) {
                try {
                    array.put((double) point);
                } catch (Exception ignored) {}
            }
        }
        return array;
    }

    private void pickLocalColor(float imageX, float imageY) {
        if (canvas == null || canvas.getBitmap() == null) return;
        Bitmap bitmap = canvas.getBitmap();
        int x = Math.max(0, Math.min(bitmap.getWidth() - 1, Math.round(imageX)));
        int y = Math.max(0, Math.min(bitmap.getHeight() - 1, Math.round(imageY)));
        int color = bitmap.getPixel(x, y);
        foregroundColor = String.format("#%06X", (0xFFFFFF & color));
        canvas.setStrokePreview(brushSize, color);
        setStatus("اللون الحالي " + foregroundColor + "  •  X " + x + " Y " + y);
    }

    private int parseColorSafe(String color) {
        try {
            return Color.parseColor(color);
        } catch (Exception ignored) {
            return Color.WHITE;
        }
    }

    private void showAddTextDialog(float x, float y) {
        if (projectId == null) {
            toast("افتح صورة أولاً");
            return;
        }

        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText textInput = new EditText(this);
        textInput.setHint("اكتب النص");
        textInput.setTextColor(TEXT);
        textInput.setHintTextColor(MUTED);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textInput.setMinLines(2);
        box.addView(textInput);

        EditText sizeInput = new EditText(this);
        sizeInput.setHint("حجم الخط");
        sizeInput.setText("42");
        sizeInput.setTextColor(TEXT);
        sizeInput.setHintTextColor(MUTED);
        sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(sizeInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إضافة نص")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إضافة", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = textInput.getText().toString();
                    if (value.trim().isEmpty()) {
                        textInput.setError("اكتب النص");
                        return;
                    }

                    double size = 42;
                    try {
                        size = Double.parseDouble(sizeInput.getText().toString());
                    } catch (Exception ignoredSize) {}

                    dialog.dismiss();
                    applyRemote("add_text", jsonOf(
                            "text", value,
                            "x", Math.round(x),
                            "y", Math.round(y),
                            "size", size,
                            "font", "Sans",
                            "color", "#ffffff"
                    ));
                }));
        dialog.show();
    }

    private String toolHelp(String id) {
        switch (id) {
            case "move": return "اسحب لتحريك المشهد. قرّب بإصبعين أو عجلة الماوس.";
            case "select": return "تحديد مستطيل مع إضافة/طرح/تقاطع التحديد.";
            case "free_select": return "ارسم حول المنطقة؛ عند رفع إصبعك يُغلق التحديد تلقائياً.";
            case "fuzzy_select": return "المس نقطة لتحديد المنطقة المتصلة ذات اللون المتشابه.";
            case "color_select": return "المس الصورة لتحديد اللون الحالي في كامل الطبقة.";
            case "color_picker": return "المس الصورة لاختيار اللون من البكسل.";
            case "zoom": return "تكبير وتصغير مع الحفاظ على مركز المؤشر.";
            default: return "خيارات الأداة ستظهر هنا حسب الأداة النشطة.";
        }
    }

    private interface SliderCommit {
        void apply(int value);
    }

    private void addSlider(LinearLayout parent, String title,
                           int min, int max, int initial, SliderCommit commit) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label(title, 13, MUTED, false);
        TextView value = label(String.valueOf(initial), 13, TEXT, true);
        value.setGravity(Gravity.END);

        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(value, new LinearLayout.LayoutParams(dp(60),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(header);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        parent.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int current = initial;
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                current = min + progress;
                value.setText(String.valueOf(current));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                if (commit != null && projectId != null) commit.apply(current);
            }
        });
    }

    private void refreshLayers() {
        if (layerList == null) return;
        layerList.removeAllViews();
        layerList.addView(label("الطبقات", 17, TEXT, true));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(actionButton("+ طبقة", v ->
                toast("الطبقات المتعددة قادمة في المحرك المحلي V2")),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(actionButton("+ مجموعة", v ->
                toast("مجموعات الطبقات قادمة في V2")),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(actionButton("+ قناع", v ->
                toast("Layer Masks قادمة في V2")),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        layerList.addView(actions);

        if (!localEngine.hasImage()) {
            layerList.addView(label("افتح صورة لعرض الطبقات", 13, MUTED, false));
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), dp(5), dp(7), dp(5));
        row.setBackground(rounded(Color.rgb(44, 49, 57), 8));

        Button eye = flatButton("◉", 14);
        TextView name = label("▤ Background", 13, TEXT, false);
        TextView meta = label("100%  •  " + localEngine.width() + "×" + localEngine.height(),
                11, MUTED, false);
        meta.setGravity(Gravity.END);

        row.addView(eye, new LinearLayout.LayoutParams(dp(42), dp(36)));
        row.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1f));
        row.addView(meta, new LinearLayout.LayoutParams(dp(132), dp(36)));
        layerList.addView(row);
    }

    private void refreshChannels() {
        if (channelList == null) return;
        channelList.removeAllViews();
        channelList.addView(label("القنوات Channels", 17, TEXT, true));

        if (!localEngine.hasImage()) {
            channelList.addView(label("افتح صورة لعرض القنوات", 13, MUTED, false));
            return;
        }

        channelList.addView(label("◉ RGB", 13, TEXT, false));
        channelList.addView(label("   R  •  G  •  B  •  Alpha", 12, MUTED, false));
        channelList.addView(label("قنوات Alpha المخصصة ستضاف في V2.", 12, MUTED, false));
    }

    private void refreshPaths() {
        if (pathList == null) return;
        pathList.removeAllViews();
        pathList.addView(label("المسارات Paths", 17, TEXT, true));
        pathList.addView(label(
                "المحرك Offline يعمل الآن. مسارات Bezier والتحويل من النص إلى مسار ضمن V2.",
                12, MUTED, false));
    }

    private void loadResources(String kind) {
        if (resourceList == null) return;
        resourceList.removeAllViews();
        resourceList.addView(label("الموارد المحلية", 17, TEXT, true));

        HorizontalScrollView picker = new HorizontalScrollView(this);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(actionButton("الخطوط", v -> loadResources("fonts")));
        buttons.addView(actionButton("الفرش", v -> loadResources("brushes")));
        buttons.addView(actionButton("التدرجات", v -> loadResources("gradients")));
        buttons.addView(actionButton("النقوش", v -> loadResources("patterns")));
        buttons.addView(actionButton("الألوان", v -> loadResources("palettes")));
        picker.addView(buttons);
        resourceList.addView(picker);

        String message;
        switch (kind) {
            case "fonts":
                message = "خطوط Android المحلية • دعم العربية متاح في طبقة النص الأساسية";
                break;
            case "brushes":
                message = "فرشاة • قلم • ممحاة تعمل محليًا الآن";
                break;
            case "gradients":
                message = "Linear Gradient يعمل محليًا الآن";
                break;
            case "patterns":
                message = "مكتبة النقوش المحلية ستضاف في V2";
                break;
            default:
                message = "ألوان المقدمة والخلفية محلية بالكامل";
                break;
        }
        resourceList.addView(label(message, 12, TEXT, false));
    }

    private void refreshRemotePanels() {
        refreshLayers();
        refreshChannels();
        refreshPaths();
    }

    private void addHistory(String item) {
        history.add(0, item);
        if (history.size() > 30) history.remove(history.size() - 1);
        if (historyList == null) return;
        historyList.removeAllViews();
        historyList.addView(label("السجل", 17, TEXT, true));
        for (String h : history) {
            TextView row = label("• " + h, 13, TEXT, false);
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            historyList.addView(row);
        }
    }

    private View buildStatusBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(desktopLayout ? 3 : 1), dp(10), dp(desktopLayout ? 3 : 1));
        row.setBackgroundColor(Color.rgb(16, 18, 21));

        statusText = label("جاهز", 12, MUTED, false);
        projectText = label("Offline • بدون صورة", 12, MUTED, false);
        zoomText = label("100%", 12, TEXT, true);
        zoomText.setGravity(Gravity.END);

        int statusHeight = desktopLayout ? 28 : 24;
        row.addView(statusText, new LinearLayout.LayoutParams(0, dp(statusHeight), 1.4f));
        row.addView(projectText, new LinearLayout.LayoutParams(0, dp(statusHeight), 1f));
        row.addView(zoomText, new LinearLayout.LayoutParams(dp(64), dp(statusHeight)));
        return row;
    }

    private void showServerDialog() {
        String imageInfo = localEngine.hasImage()
                ? localEngine.width() + "×" + localEngine.height() +
                  "  •  Undo " + localEngine.undoDepth() +
                  "  •  Redo " + localEngine.redoDepth()
                : "لا توجد صورة مفتوحة";

        new AlertDialog.Builder(this)
                .setTitle("UNB Pro Editor • Offline")
                .setMessage("المحرر يعمل داخل الهاتف بالكامل.\n\n" +
                        imageInfo + "\n\n" +
                        "لا يوجد سيرفر، لا رفع صور، ولا يحتاج إنترنت.")
                .setNegativeButton("إغلاق", null)
                .setPositiveButton("معلومات المحرك", (d, w) ->
                        toast("LocalEditorEngine • Android Canvas/Bitmap"))
                .show();
    }

    private void chooseImage() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CREATE_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null &&
                    pendingExportBytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) throw new Exception("تعذر فتح ملف الحفظ");
                    out.write(pendingExportBytes);
                    out.flush();
                    toast("تم حفظ " + pendingExportName);
                    setStatus("تم حفظ الملف في الهاتف");
                } catch (Exception e) {
                    toast("فشل الحفظ: " + e.getMessage());
                }
            }
            pendingExportBytes = null;
            return;
        }

        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;

        Uri uri = data.getData();
        setBusy(true);
        setStatus("جاري فتح الصورة محليًا...");

        localExecutor.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                String name = queryName(uri);
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (decoded == null) throw new Exception("صيغة الصورة غير مدعومة");

                localEngine.open(decoded);
                Bitmap frame = localEngine.current();

                runOnUiThread(() -> {
                    sourceBytes = bytes;
                    sourceName = name;
                    projectId = "LOCAL";
                    canvas.setBitmap(frame);
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());
                    setBusy(false);
                    setStatus("المحرر المحلي جاهز — بدون سيرفر");
                    addHistory("فتح " + sourceName);
                    refreshRemotePanels();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    toast("تعذر فتح الصورة: " + e.getMessage());
                    setStatus("فشل فتح الصورة");
                });
            }
        });
    }

    private void applyRemote(String operation, JSONObject params) {
        if (!localEngine.hasImage()) {
            toast("افتح صورة أولاً");
            return;
        }
        if (busy) return;

        setBusy(true);
        setStatus("تنفيذ " + operation + " محليًا...");

        localExecutor.execute(() -> {
            try {
                Bitmap value = localEngine.apply(operation, params);
                runOnUiThread(() -> {
                    canvas.setBitmap(value);
                    if (canvas != null) canvas.clearStrokePreview();
                    setBusy(false);
                    projectId = "LOCAL";
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());
                    setStatus("تم " + operation + " محليًا");
                    addHistory(operation);
                    refreshRemotePanels();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (canvas != null) canvas.clearStrokePreview();
                    setBusy(false);
                    setStatus("غير متاح بعد: " + shortText(e.getMessage()));
                    toast(shortText(e.getMessage()));
                });
            }
        });
    }

    private void remoteHistory(String action) {
        if (!localEngine.hasImage() || busy) return;

        setBusy(true);
        setStatus("جاري " + action + " محليًا...");

        localExecutor.execute(() -> {
            try {
                Bitmap value = "redo".equals(action)
                        ? localEngine.redo()
                        : localEngine.undo();

                runOnUiThread(() -> {
                    canvas.setBitmap(value);
                    setBusy(false);
                    projectText.setText("Offline • " +
                            localEngine.width() + "×" + localEngine.height());
                    setStatus("تم " + action + " محليًا");
                    addHistory(action);
                    refreshRemotePanels();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus(shortText(e.getMessage()));
                    toast(shortText(e.getMessage()));
                });
            }
        });
    }

    private void exportProject(String format) {
        if (!localEngine.hasImage()) {
            toast("لا توجد صورة للحفظ");
            return;
        }
        if (busy) return;

        String fmt = format == null ? "png" : format.toLowerCase();
        if (!("png".equals(fmt) || "jpg".equals(fmt) ||
                "jpeg".equals(fmt) || "webp".equals(fmt))) {
            toast("هذه الصيغة ستضاف في المحرك المحلي القادم");
            return;
        }

        setBusy(true);
        setStatus("جاري التصدير محليًا بصيغة " + fmt.toUpperCase() + "...");

        localExecutor.execute(() -> {
            try {
                byte[] value = localEngine.export(fmt, 95);
                runOnUiThread(() -> {
                    setBusy(false);
                    pendingExportBytes = value;
                    pendingExportName = "UNB_Pro_Editor_" + System.currentTimeMillis() + "." +
                            ("jpeg".equals(fmt) ? "jpg" : fmt);

                    String mime;
                    switch (fmt) {
                        case "jpg":
                        case "jpeg":
                            mime = "image/jpeg";
                            break;
                        case "webp":
                            mime = "image/webp";
                            break;
                        default:
                            mime = "image/png";
                    }

                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(mime);
                    save.putExtra(Intent.EXTRA_TITLE, pendingExportName);
                    startActivityForResult(save, CREATE_EXPORT);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("فشل التصدير: " + shortText(e.getMessage()));
                    toast("فشل التصدير");
                });
            }
        });
    }

    private void pingServer() {
        setStatus("UNB Pro Editor • Offline 100%");
        if (projectText != null && !localEngine.hasImage()) {
            projectText.setText("Offline • بدون صورة");
        }
    }

    private void showMenuGroup(String group) {
        switch (group) {
            case "ملف":
                showChoice("ملف", new String[]{
                        "فتح صورة", "حفظ PNG", "حفظ JPG", "حفظ WEBP", "حفظ XCF"
                }, index -> {
                    if (index == 0) chooseImage();
                    else if (index == 1) exportProject("png");
                    else if (index == 2) exportProject("jpg");
                    else if (index == 3) exportProject("webp");
                    else if (index == 4) exportProject("xcf");
                });
                break;

            case "تعديل":
                showChoice("تعديل", new String[]{"تراجع", "إعادة"}, index ->
                        remoteHistory(index == 0 ? "undo" : "redo"));
                break;

            case "تحديد":
                showChoice("تحديد", new String[]{
                        "تحديد الكل", "إلغاء التحديد", "عكس التحديد",
                        "توسيع 5px", "تقليص 5px", "Feather 5px",
                        "حدود 2px", "Sharpen"
                }, index -> {
                    switch (index) {
                        case 0: applyRemote("select_all", new JSONObject()); break;
                        case 1: applyRemote("select_none", new JSONObject()); break;
                        case 2: applyRemote("invert_selection", new JSONObject()); break;
                        case 3: applyRemote("grow", json("steps", 5)); break;
                        case 4: applyRemote("shrink", json("steps", 5)); break;
                        case 5: applyRemote("feather", json("radius", 5)); break;
                        case 6: applyRemote("border", json("radius", 2)); break;
                        case 7: applyRemote("sharpen_selection", new JSONObject()); break;
                    }
                });
                break;

            case "عرض":
                showChoice("عرض", new String[]{"ملاءمة للصورة", "100%", "تكبير 125%", "تصغير 80%"},
                        index -> {
                            if (canvas == null) return;
                            if (index == 0) canvas.fitToView();
                            else if (index == 1) canvas.setZoom(1f);
                            else if (index == 2) canvas.setZoom(canvas.getZoom() * 1.25f);
                            else canvas.setZoom(canvas.getZoom() * 0.8f);
                        });
                break;

            case "صورة":
                showChoice("صورة", new String[]{
                        "تدوير 90° يمين", "تدوير 90° يسار", "تدوير 180°",
                        "عكس أفقي", "عكس عمودي", "تغيير الحجم"
                }, index -> {
                    if (index == 0) applyRemote("rotate", json("degrees", 90));
                    else if (index == 1) applyRemote("rotate", json("degrees", -90));
                    else if (index == 2) applyRemote("rotate", json("degrees", 180));
                    else if (index == 3) applyRemote("flip_horizontal", new JSONObject());
                    else if (index == 4) applyRemote("flip_vertical", new JSONObject());
                    else showResizeDialog();
                });
                break;

            case "طبقة":
                showChoice("طبقة", new String[]{
                        "فتح لوحة الطبقات", "طبقة جديدة", "مجموعة جديدة",
                        "إضافة قناع أبيض", "دمج المرئي", "Flatten"
                }, index -> {
                    if (index == 0) {
                        openInspectorTab("layers");
                        refreshLayers();
                    } else if (index == 1) {
                        applyRemote("add_layer", jsonOf("name", "Layer"));
                    } else if (index == 2) {
                        applyRemote("add_group", jsonOf("name", "Group"));
                    } else if (index == 3) {
                        applyRemote("add_mask", jsonOf("type", "white"));
                    } else if (index == 4) {
                        applyRemote("merge_visible", new JSONObject());
                    } else {
                        applyRemote("flatten", new JSONObject());
                    }
                });
                break;

            case "ألوان":
                showChoice("ألوان", new String[]{
                        "Brightness / Contrast", "Hue / Saturation",
                        "Levels", "Curves", "Threshold", "Posterize",
                        "Desaturate", "Invert", "Equalize", "Color Balance"
                }, index -> {
                    if (index == 0 || index == 1) {
                        openInspectorTab("properties");
                    } else if (index == 2) {
                        applyRemote("levels", new JSONObject());
                    } else if (index == 3) {
                        applyRemote("curves", new JSONObject());
                    } else if (index == 4) {
                        showNumericOperationDialog("Threshold 0-100", "threshold", "low", 50, 0, 100, true);
                    } else if (index == 5) {
                        showNumericOperationDialog("Posterize 2-256", "posterize", "levels", 4, 2, 256, false);
                    } else if (index == 6) {
                        applyRemote("desaturate", new JSONObject());
                    } else if (index == 7) {
                        applyRemote("invert", new JSONObject());
                    } else if (index == 8) {
                        applyRemote("equalize", new JSONObject());
                    } else {
                        applyRemote("color_balance", new JSONObject());
                    }
                });
                break;

            case "أدوات":
                showChoice("أدوات", new String[]{
                        "تحريك", "تحديد", "لاسو", "تحديد سحري", "حسب اللون",
                        "فرشاة", "قلم", "ممحاة", "تدرج", "نص",
                        "Clone", "Heal", "Smudge", "Dodge/Burn", "Color Picker"
                }, index -> {
                    String[] ids = {
                            "move", "select", "free_select", "fuzzy_select", "color_select",
                            "brush", "pencil", "eraser", "gradient", "text",
                            "clone", "heal", "smudge", "dodge_burn", "color_picker"
                    };
                    String[] names = {
                            "تحريك", "تحديد", "لاسو", "سحري", "حسب لون",
                            "فرشاة", "قلم", "ممحاة", "تدرج", "نص",
                            "استنساخ", "ترميم", "تلطيخ", "إضاءة", "لون"
                    };
                    if (index >= 0 && index < ids.length) showTool(ids[index], names[index], null);
                });
                break;

            case "فلاتر":
                showChoice("فلاتر GEGL", new String[]{
                        "Gaussian Blur", "Unsharp Mask", "Noise Reduction",
                        "Bloom", "Emboss", "Edge", "Oilify", "Pixelize",
                        "Mosaic", "Motion Blur", "Color Temperature"
                }, index -> {
                    String[] ops = {
                            "gaussian_blur", "unsharp_mask", "noise_reduction",
                            "bloom", "emboss", "edge", "oilify", "pixelize",
                            "mosaic", "motion_blur", "color_temperature"
                    };
                    if (index >= 0 && index < ops.length) {
                        applyRemote(ops[index], new JSONObject());
                    }
                });
                break;

            case "نوافذ":
                showChoice("نوافذ", new String[]{
                        "خصائص", "طبقات", "قنوات", "مسارات", "موارد", "السجل"
                }, index -> {
                    String[] tabs = {"properties", "layers", "channels", "paths", "resources", "history"};
                    if (index < 0 || index >= tabs.length) return;
                    openInspectorTab(tabs[index]);
                    if (index == 1) refreshLayers();
                    else if (index == 2) refreshChannels();
                    else if (index == 3) refreshPaths();
                    else if (index == 4) loadResources("fonts");
                });
                break;

            default:
                toast("القائمة غير متاحة");
        }
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    private void showChoice(String title, String[] items, ChoiceHandler handler) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> {
                    if (handler != null) handler.onChoice(which);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showResizeDialog() {
        if (projectId == null) {
            toast("افتح صورة أولاً");
            return;
        }

        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText width = new EditText(this);
        width.setHint("العرض px");
        width.setInputType(InputType.TYPE_CLASS_NUMBER);
        width.setTextColor(TEXT);
        width.setHintTextColor(MUTED);

        EditText height = new EditText(this);
        height.setHint("الارتفاع px");
        height.setInputType(InputType.TYPE_CLASS_NUMBER);
        height.setTextColor(TEXT);
        height.setHintTextColor(MUTED);

        if (canvas != null && canvas.getBitmap() != null) {
            width.setText(String.valueOf(canvas.getBitmap().getWidth()));
            height.setText(String.valueOf(canvas.getBitmap().getHeight()));
        }

        box.addView(width);
        box.addView(height);

        new AlertDialog.Builder(this)
                .setTitle("تغيير حجم الصورة")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تطبيق", (dialog, which) -> {
                    try {
                        int w = Integer.parseInt(width.getText().toString());
                        int h = Integer.parseInt(height.getText().toString());
                        applyRemote("resize", jsonOf("width", w, "height", h));
                    } catch (Exception e) {
                        toast("أدخل عرضاً وارتفاعاً صحيحين");
                    }
                })
                .show();
    }

    private void showNumericOperationDialog(String title, String operation, String key,
                                            int initial, int min, int max, boolean normalize100) {
        EditText value = new EditText(this);
        value.setText(String.valueOf(initial));
        value.setInputType(InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL |
                InputType.TYPE_NUMBER_FLAG_SIGNED);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(value)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تطبيق", (dialog, which) -> {
                    try {
                        double v = Double.parseDouble(value.getText().toString());
                        v = Math.max(min, Math.min(max, v));
                        if (normalize100) v /= 100.0;
                        applyRemote(operation, json(key, v));
                    } catch (Exception e) {
                        toast("قيمة غير صحيحة");
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        localExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_O:
                    chooseImage();
                    return true;
                case KeyEvent.KEYCODE_S:
                    exportProject("png");
                    return true;
                case KeyEvent.KEYCODE_Z:
                    remoteHistory(event.isShiftPressed() ? "redo" : "undo");
                    return true;
                case KeyEvent.KEYCODE_0:
                    if (canvas != null) canvas.fitToView();
                    return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && canvas != null) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_PLUS ||
                    event.getKeyCode() == KeyEvent.KEYCODE_EQUALS) {
                canvas.setZoom(canvas.getZoom() * 1.2f);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MINUS) {
                canvas.setZoom(canvas.getZoom() / 1.2f);
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isDesktopLayout() {
        Configuration c = getResources().getConfiguration();
        return c.smallestScreenWidthDp >= 600 ||
                c.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void setBusy(boolean value) {
        busy = value;
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private String activeToolLabel() {
        return toolTitle == null ? activeTool : toolTitle.getText().toString();
    }

    private JSONObject json(String key, Object value) {
        JSONObject o = new JSONObject();
        try { o.put(key, value); } catch (Exception ignored) {}
        return o;
    }

    private JSONObject jsonOf(Object... pairs) {
        JSONObject o = new JSONObject();
        if (pairs == null) return o;
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception ignored) {}
        return o;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("تعذر قراءة الملف");
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        String name = "image.png";
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {}
        return name == null ? "image.png" : name;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button b = flatButton(text, desktopLayout ? 12 : 11);
        b.setBackground(rounded(PANEL_2, 8));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(desktopLayout ? 40 : 36));
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private Button flatButton(String text, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setPadding(dp(desktopLayout ? 11 : 8), 0, dp(desktopLayout ? 11 : 8), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private Button tabButton(String text) {
        Button b = flatButton(text, 12);
        b.setBackground(rounded(PANEL_2, 8));
        return b;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private View dividerVertical() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(1),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    private View verticalDivider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(28));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String shortText(String s) {
        if (s == null) return "خطأ";
        s = s.replace("\n", " ").trim();
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
