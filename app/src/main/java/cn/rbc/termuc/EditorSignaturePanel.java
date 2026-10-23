package cn.rbc.termuc;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import androidx.core.graphics.ColorUtils;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

public final class EditorSignaturePanel {
    private final TextEditor editor;
    private final PopupWindow popup;
    private final TextView text;
    private final Button previous, next;
    private final LinearLayout root;
    private List<String> signatures = Collections.emptyList();
    private int selected;

    EditorSignaturePanel(TextEditor editor) {
        this.editor = editor;
        int unit = (int)(editor.getResources().getDisplayMetrics().density * 48);
        root = new LinearLayout(editor.getContext());
        float density = editor.getResources().getDisplayMetrics().density;
        root.setPadding((int)(12 * density), (int)(8 * density), (int)(12 * density), (int)(8 * density));
        previous = new Button(editor.getContext()); previous.setText("‹");
        next = new Button(editor.getContext()); next.setText("›");
        previous.setContentDescription(editor.getContext().getString(R.string.previous_signature));
        next.setContentDescription(editor.getContext().getString(R.string.next_signature));
        text = new TextView(editor.getContext()); text.setTypeface(Application.typeface());
        root.addView(previous, new LinearLayout.LayoutParams(unit, unit));
        root.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(next, new LinearLayout.LayoutParams(unit, unit));
        previous.setOnClickListener(view -> select(selected - 1));
        next.setOnClickListener(view -> select(selected + 1));
        popup = new PopupWindow(root, Math.min(editor.getResources().getDisplayMetrics().widthPixels,
            (int)(480 * editor.getResources().getDisplayMetrics().density)), ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setOutsideTouchable(true);
    }
    public void show(List<String> values, int index) {
        if (values == null || values.isEmpty() || !editor.isShown() || !editor.isAttachedToWindow()) { hide(); return; }
        signatures = values; select(Math.max(0, Math.min(index, values.size() - 1)));
        popup.showAsDropDown(editor, 0, -editor.getHeight() + editor.getBoundingBox(editor.getCaretPosition()).bottom);
    }
    private void select(int index) {
        if (index < 0 || index >= signatures.size()) return;
        selected = index; text.setText(signatures.get(index));
        previous.setEnabled(index > 0); next.setEnabled(index + 1 < signatures.size());
        previous.setVisibility(signatures.size() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        next.setVisibility(previous.getVisibility());
    }
    public void setColors(int background, int foreground, int primary) {
        float density = editor.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable(); shape.setColor(background); shape.setCornerRadius(12 * density);
        popup.setBackgroundDrawable(shape); text.setTextColor(foreground);
        for (Button button : new Button[]{previous, next}) {
            button.setPadding(0, 0, 0, 0);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{ColorUtils.setAlphaComponent(foreground, 97), foreground}));
            GradientDrawable mask = new GradientDrawable(); mask.setColor(android.graphics.Color.WHITE);
            mask.setCornerRadius(24 * density);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 31)), null, mask));
        }
    }
    public void setTextSize(float pixels) { text.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels); }
    public boolean isShowing() { return popup.isShowing(); }
    public void hide() { popup.dismiss(); }
}
