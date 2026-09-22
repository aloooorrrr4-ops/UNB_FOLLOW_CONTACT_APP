package com.unb.imageeditor;

import android.app.Activity;
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
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProfessionalEditorActivity extends Activity {

    private static final int PICK_IMAGE = 4101;

    private final int BG = Color.rgb(20, 22, 26);
    private final int PANEL = Color.rgb(28, 31, 36);
    private final int PANEL_2 = Color.rgb(35, 39, 45);
    private final int LINE = Color.rgb(55, 60, 68);
    private final int TEXT = Color.rgb(239, 242, 246);
    private final int MUTED = Color.rgb(159, 167, 178);
    private final int ACCENT = Color.rgb(61, 139, 255);

    private EditorCanvasView canvas;
    private EditorApiClient api;
    private String projectId;
    private byte[] sourceBytes;
    private String sourceName = "image.png";

    private TextView statusText;
    private TextView zoomText;
    private TextView projectText;
    private TextView toolTitle;
    private LinearLayout toolOptions;
    private LinearLayout layerList;
    private LinearLayout historyList;
    private Button activeToolButton;
    private String activeTool = "move";

    private final List<String> history = new ArrayList<>();
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(14, 16, 19));
        getWindow().setNavigationBarColor(Color.rgb(14, 16, 19));

        api = new EditorApiClient("http://91.98.126.167:18085");

        LinearLayout root = column();
        root.setBackgroundColor(BG);

        root.addView(buildMenuBar());
        root.addView(divider());
        root.addView(buildQuickBar());
        root.addView(divider());

        boolean desktop = isDesktopLayout();
        View workspace = desktop ? buildDesktopWorkspace() : buildPhoneWorkspace();
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
        row.setPadding(dp(6), dp(4), dp(6), dp(4));

        String[] menus = {"ملف", "تعديل", "تحديد", "عرض", "صورة", "طبقة", "ألوان", "أدوات", "فلاتر", "نوافذ"};
        for (String name : menus) {
            Button b = flatButton(name, 13);
            b.setOnClickListener(v -> showMenuGroup(name));
            row.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
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
        row.setPadding(dp(6), dp(5), dp(6), dp(5));

        row.addView(actionButton("فتح", v -> chooseImage()));
        row.addView(actionButton("حفظ", v -> exportProject("png")));
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
        middle.addView(buildToolRail(), new LinearLayout.LayoutParams(dp(74),
                ViewGroup.LayoutParams.MATCH_PARENT));
        middle.addView(dividerVertical());

        canvas = createCanvas();
        middle.addView(canvas, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        root.addView(middle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(divider());
        View inspector = buildInspector();
        root.addView(inspector, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(218)));
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
                if ("color_picker".equals(activeTool)) {
                    setStatus("التقاط لون عند " + Math.round(imageX) + ", " + Math.round(imageY));
                }
            }
        });
        return v;
    }

    private View buildToolRail() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PANEL);

        LinearLayout rail = column();
        rail.setPadding(dp(6), dp(7), dp(6), dp(8));

        addTool(rail, "move", "تحريك");
        addTool(rail, "select", "تحديد");
        addTool(rail, "free_select", "حر");
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
        b.setTextSize(11);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(3), dp(2), dp(3));
        b.setBackground(rounded(PANEL_2, 9));
        b.setTag(id);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.bottomMargin = dp(5);
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
        tabs.setPadding(dp(5), dp(5), dp(5), dp(2));

        Button props = tabButton("خصائص");
        Button layers = tabButton("طبقات");
        Button hist = tabButton("السجل");

        tabs.addView(props, new LinearLayout.LayoutParams(0, dp(40), 1f));
        tabs.addView(layers, new LinearLayout.LayoutParams(0, dp(40), 1f));
        tabs.addView(hist, new LinearLayout.LayoutParams(0, dp(40), 1f));
        panel.addView(tabs);

        ScrollView bodyScroll = new ScrollView(this);
        LinearLayout body = column();
        body.setPadding(dp(12), dp(8), dp(12), dp(12));
        bodyScroll.addView(body);
        panel.addView(bodyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        toolOptions = column();
        layerList = column();
        historyList = column();

        body.addView(toolOptions);
        body.addView(layerList);
        body.addView(historyList);

        props.setOnClickListener(v -> showInspector("properties"));
        layers.setOnClickListener(v -> showInspector("layers"));
        hist.setOnClickListener(v -> showInspector("history"));

        showInspector("properties");
        return panel;
    }

    private void showInspector(String tab) {
        if (toolOptions == null) return;
        toolOptions.setVisibility("properties".equals(tab) ? View.VISIBLE : View.GONE);
        layerList.setVisibility("layers".equals(tab) ? View.VISIBLE : View.GONE);
        historyList.setVisibility("history".equals(tab) ? View.VISIBLE : View.GONE);
    }

    private void showTool(String id, String label, Button source) {
        if (source != null && activeToolButton != source) {
            if (activeToolButton != null) activeToolButton.setBackground(rounded(PANEL_2, 9));
            activeToolButton = source;
            source.setBackground(rounded(ACCENT, 9));
        }
        activeTool = id;
        if (toolOptions == null) return;

        toolOptions.removeAllViews();
        toolTitle = label("أداة: " + label, 17, TEXT, true);
        toolOptions.addView(toolTitle);

        if (Arrays.asList("brush", "pencil", "eraser", "clone", "heal", "smudge").contains(id)) {
            addSlider(toolOptions, "الحجم", 1, 300, 45, null);
            addSlider(toolOptions, "العتامة", 0, 100, 100, null);
            addSlider(toolOptions, "الصلابة", 0, 100, 70, null);
            addSlider(toolOptions, "التباعد", 1, 200, 20, null);
        } else if ("text".equals(id)) {
            addSlider(toolOptions, "حجم الخط", 6, 300, 42, null);
            addSlider(toolOptions, "تباعد الحروف", 0, 100, 0, null);
            addSlider(toolOptions, "تباعد الأسطر", 0, 150, 20, null);
            toolOptions.addView(actionButton("اختيار الخط", v -> toast("لوحة الخطوط")));
            toolOptions.addView(actionButton("RTL / LTR", v -> toast("اتجاه النص")));
        } else if ("crop".equals(id) || "transform".equals(id) || "perspective".equals(id)) {
            toolOptions.addView(actionButton("تطبيق", v -> toast("يتم ربط أبعاد التحويل بالـ Canvas")));
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

    private String toolHelp(String id) {
        switch (id) {
            case "move": return "اسحب لتحريك المشهد. قرّب بإصبعين أو عجلة الماوس.";
            case "select": return "تحديد مستطيل مع إضافة/طرح/تقاطع التحديد.";
            case "free_select": return "تحديد حر ومسارات متعددة النقاط.";
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
        actions.addView(actionButton("+ طبقة", v -> toast("إضافة طبقة")), new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(actionButton("+ مجموعة", v -> toast("إضافة مجموعة")), new LinearLayout.LayoutParams(0, dp(44), 1f));
        layerList.addView(actions);

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8), dp(8), dp(8), dp(8));
        item.setBackground(rounded(Color.rgb(44, 49, 57), 8));

        TextView eye = label("◉", 16, TEXT, false);
        TextView name = label(projectId == null ? "لا يوجد مشروع" : "الصورة الأساسية", 14, TEXT, false);
        item.addView(eye, new LinearLayout.LayoutParams(dp(34), dp(38)));
        item.addView(name, new LinearLayout.LayoutParams(0, dp(38), 1f));
        layerList.addView(item);

        layerList.addView(label("Opacity 100%   •   Normal", 12, MUTED, false));
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
        row.setPadding(dp(10), dp(3), dp(10), dp(3));
        row.setBackgroundColor(Color.rgb(16, 18, 21));

        statusText = label("جاهز", 12, MUTED, false);
        projectText = label("بدون مشروع", 12, MUTED, false);
        zoomText = label("100%", 12, TEXT, true);
        zoomText.setGravity(Gravity.END);

        row.addView(statusText, new LinearLayout.LayoutParams(0, dp(28), 1.4f));
        row.addView(projectText, new LinearLayout.LayoutParams(0, dp(28), 1f));
        row.addView(zoomText, new LinearLayout.LayoutParams(dp(70), dp(28)));
        return row;
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
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;

        Uri uri = data.getData();
        try {
            sourceBytes = readAll(uri);
            sourceName = queryName(uri);
            Bitmap bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.length);
            if (bitmap == null) throw new Exception("صيغة الصورة غير مدعومة");
            canvas.setBitmap(bitmap);
            setStatus("جاري إنشاء مشروع على السيرفر...");
            setBusy(true);

            api.createProject(sourceBytes, sourceName, new EditorApiClient.Callback<EditorApiClient.Project>() {
                @Override
                public void onSuccess(EditorApiClient.Project p) {
                    runOnUiThread(() -> {
                        projectId = p.id;
                        if (p.preview != null) canvas.setBitmap(p.preview);
                        projectText.setText("Project " + projectId.substring(0, Math.min(8, projectId.length())));
                        setBusy(false);
                        setStatus("المشروع متصل بالسيرفر");
                        addHistory("فتح " + sourceName);
                        refreshLayers();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        setStatus("الصورة محليًا — السيرفر: " + shortText(message));
                        addHistory("فتح محلي " + sourceName);
                        refreshLayers();
                    });
                }
            });
        } catch (Exception e) {
            toast("تعذر فتح الصورة: " + e.getMessage());
        }
    }

    private void applyRemote(String operation, JSONObject params) {
        if (projectId == null) {
            toast("افتح صورة وانتظر إنشاء المشروع على السيرفر");
            return;
        }
        if (busy) return;

        setBusy(true);
        setStatus("تنفيذ " + operation + " على السيرفر...");

        api.applyOperation(projectId, operation, params,
                new EditorApiClient.Callback<Bitmap>() {
                    @Override
                    public void onSuccess(Bitmap value) {
                        runOnUiThread(() -> {
                            canvas.setBitmap(value);
                            setBusy(false);
                            setStatus("تم " + operation);
                            addHistory(operation);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            setStatus("فشل: " + shortText(message));
                        });
                    }
                });
    }

    private void remoteHistory(String action) {
        if (projectId == null || busy) return;
        setBusy(true);
        setStatus("جاري " + action + "...");

        api.history(projectId, action, new EditorApiClient.Callback<Bitmap>() {
            @Override
            public void onSuccess(Bitmap value) {
                runOnUiThread(() -> {
                    canvas.setBitmap(value);
                    setBusy(false);
                    setStatus("تم " + action);
                    addHistory(action);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("فشل: " + shortText(message));
                });
            }
        });
    }

    private void exportProject(String format) {
        if (projectId == null) {
            toast("لا يوجد مشروع للحفظ");
            return;
        }
        toast("التصدير النهائي سيتم من محرك GIMP على السيرفر");
        setStatus("المشروع جاهز للتصدير " + format.toUpperCase());
    }

    private void pingServer() {
        api.health(new EditorApiClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                runOnUiThread(() -> setStatus("السيرفر متصل"));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> setStatus("واجهة جاهزة — السيرفر غير متصل بالمحرر الجديد بعد"));
            }
        });
    }

    private void showMenuGroup(String group) {
        switch (group) {
            case "ملف":
                toast("جديد • فتح • استيراد • حفظ • حفظ باسم • تصدير");
                break;
            case "تعديل":
                toast("تراجع • إعادة • قص • نسخ • لصق • تفضيلات");
                break;
            case "تحديد":
                toast("الكل • لا شيء • عكس • توسيع • تقليص • Feather");
                break;
            case "صورة":
                toast("Mode • Canvas Size • Scale • Rotate • Guides");
                break;
            case "طبقة":
                showInspector("layers");
                toast("طبقة جديدة • Mask • Group • Merge • Blend");
                break;
            case "ألوان":
                toast("Levels • Curves • Exposure • Hue/Saturation • Color Balance");
                break;
            case "أدوات":
                toast("Transform • Paint • Text • Clone • Heal • Paths");
                break;
            case "فلاتر":
                toast("Blur • Enhance • Distort • Noise • Edge • Render • GEGL");
                break;
            case "نوافذ":
                toast("Layers • Channels • Paths • Brushes • Fonts • History");
                break;
            default:
                toast("Zoom • Grid • Guides • Snap • Fullscreen");
        }
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
        Button b = flatButton(text, 12);
        b.setBackground(rounded(PANEL_2, 8));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
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
        b.setPadding(dp(11), 0, dp(11), 0);
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
