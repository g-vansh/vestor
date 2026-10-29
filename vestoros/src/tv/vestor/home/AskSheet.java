package tv.vestor.home;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Ask Vestor's question box on the TV, in Vestor's own glass and type rather than a system dialog.
 * It sits at the top of the screen with the Fire TV keyboard open below it, so a question can be
 * typed at once or spoken: holding the remote's microphone button while Amazon's own keyboard is up
 * types what you say, on the Fire OS builds that allow it. Fire OS gives apps no other way to hear
 * the remote, and the microphone button itself always belongs to Alexa. So "Speak on your phone"
 * hands the listening to a phone: the phone page shows a Speak button, the phone's own keyboard
 * microphone turns speech into text, and the words appear in this box as they are said. Under the
 * box, a few questions to pick with the remote, and OK on one asks it.
 *
 * Built from ordinary Android views, so the keyboard, focus and Back behave as they do everywhere
 * on Fire TV.
 */
final class AskSheet {
    interface Asker {
        void ask(String question);

        /** "Speak on your phone" was chosen. Answers whether the phones were told. */
        boolean onPhone();

        /** The box closed, by an answer, Back or anything else. */
        void closed();
    }

    /** The open box, so words from a phone can be shown in it. Main thread. */
    static final class Sheet {
        final Dialog dialog;
        private final EditText field;
        private final TextView hint, micLabel;
        private boolean listening, heard;

        private Sheet(Dialog dialog, EditText field, TextView hint, TextView micLabel) {
            this.dialog = dialog;
            this.field = field;
            this.hint = hint;
            this.micLabel = micLabel;
        }

        boolean isShowing() {
            return dialog.isShowing();
        }

        void dismiss() {
            dialog.dismiss();
        }

        /** Words from a phone, typed or spoken, replacing what the box holds. */
        void draft(String text) {
            if (!dialog.isShowing() || text.equals(field.getText().toString())) return;
            field.setText(text);
            field.setSelection(field.length());
            if (!heard) {
                heard = true;
                hint.setText("From your phone. Tap send there, or press OK on Ask this.");
            }
            micLabel.setText(Text.clean(text).isEmpty() ? "Listening on your phone" : "Ask this");
        }

        private void listen(boolean told) {
            listening = true;
            micLabel.setText("Listening on your phone");
            hint.setText((told ? "Your phones were told. " : "") + "Open Vestor on your phone and tap Speak. Your words show here as you talk.");
        }
    }

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private AskSheet() {
    }

    /** Opens the box. {@code ideas} are offered under it, at most three. */
    static Sheet show(Activity a, List<String> ideas, final Asker asker) {
        final float d = a.getResources().getDisplayMetrics().density;
        final boolean day = Tone.mix > 0.5f;
        final AndroidGfx gfx = new AndroidGfx(a.getAssets());
        final Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int primary = Tone.text(Tone.PRIMARY, 1), secondary = Tone.text(Tone.SECONDARY, 1), tertiary = Tone.text(Tone.TERTIARY, 1);
        int wash = day ? 0x0F000000 : 0x14FFFFFF, edge = day ? 0x24000000 : 0x29FFFFFF;

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(d, 40), px(d, 30), px(d, 40), px(d, 28));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(px(d, 28));
        glass.setColor(day ? 0xF5F3EFE8 : 0xF21D1C1A);
        glass.setStroke(Math.max(1, px(d, 1)), day ? 0x1F000000 : 0x24FFFFFF);
        box.setBackground(glass);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new Sparkle(a, gfx, Tone.ACCENT), new LinearLayout.LayoutParams(px(d, 26), px(d, 26)));
        TextView label = text(a, "ASK VESTOR", gfx.face(Gfx.MONO), 13, secondary);
        label.setLetterSpacing(0.12f);
        head.addView(label, margins(d, 12, 0, 0, 0));
        box.addView(head);

        box.addView(text(a, "What would you like?", gfx.face(Gfx.SERIF), 34, primary), margins(d, 0, 10, 0, 18));

        final EditText field = new EditText(a);
        field.setTypeface(gfx.face(Gfx.TEXT_MEDIUM));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        field.setTextColor(primary);
        field.setHintTextColor(tertiary);
        field.setHint("Type, or speak on your phone");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Claude.MAX_QUESTION)});
        field.setPadding(px(d, 18), px(d, 14), px(d, 18), px(d, 14));
        field.setBackground(states(pill(d, 16, wash, Tone.ACCENT, 2), pill(d, 16, wash, edge, 1)));
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                boolean enter = e != null && e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (action != EditorInfo.IME_ACTION_SEND && action != EditorInfo.IME_ACTION_DONE && action != EditorInfo.IME_ACTION_GO
                        && !enter) {
                    return false;
                }
                String q = Text.clean(field.getText().toString());
                if (q.isEmpty()) return true;
                dialog.dismiss();
                asker.ask(q);
                return true;
            }
        });
        box.addView(field, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView hint = text(a, "Down for Speak on your phone. Holding the remote's mic button with the keyboard up also types, where Fire OS allows it.",
                gfx.face(Gfx.TEXT_REGULAR), 14, tertiary);
        box.addView(hint, margins(d, 2, 10, 0, 0));

        // Speak on your phone: the remote's microphone is Alexa's, a phone's keyboard microphone is not.
        ColorStateList ink = new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {Tone.INK, primary});
        final LinearLayout mic = new LinearLayout(a);
        mic.setOrientation(LinearLayout.HORIZONTAL);
        mic.setGravity(Gravity.CENTER_VERTICAL);
        mic.setPadding(px(d, 14), px(d, 9), px(d, 18), px(d, 9));
        mic.setBackground(states(pill(d, 20, Tone.FOCUS, 0, 0), pill(d, 20, 0, Tone.ACCENT, 1.5f)));
        mic.setFocusable(true);
        mic.setClickable(true);
        final Glyph micIcon = new Glyph(a, gfx, Tone.INK, Tone.ACCENT);
        mic.addView(micIcon, new LinearLayout.LayoutParams(px(d, 22), px(d, 22)));
        TextView micLabel = text(a, "Speak on your phone", gfx.face(Gfx.TEXT_MEDIUM), 16, primary);
        micLabel.setTextColor(ink);
        micLabel.setDuplicateParentStateEnabled(true);
        mic.addView(micLabel, margins(d, 8, 0, 0, 0));
        final Sheet sheet = new Sheet(dialog, field, hint, micLabel);
        mic.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                micIcon.invalidate();
            }
        });
        mic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String q = Text.clean(field.getText().toString());
                if (sheet.heard && !q.isEmpty()) {
                    dialog.dismiss();
                    asker.ask(q);
                    return;
                }
                if (!sheet.listening) sheet.listen(asker.onPhone());
            }
        });
        box.addView(mic, margins(d, 0, 18, 0, 0));

        if (!ideas.isEmpty()) {
            TextView pick = text(a, "OR PICK ONE", gfx.face(Gfx.MONO), 11.5f, tertiary);
            pick.setLetterSpacing(0.12f);
            box.addView(pick, margins(d, 0, 22, 0, 10));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < ideas.size() && i < 3; i++) {
                final String q = ideas.get(i);
                TextView chip = text(a, q, gfx.face(Gfx.TEXT_MEDIUM), 16, primary);
                chip.setTextColor(ink);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setMaxWidth(px(d, 236));
                chip.setPadding(px(d, 16), px(d, 9), px(d, 16), px(d, 9));
                chip.setBackground(states(pill(d, 20, Tone.FOCUS, 0, 0), pill(d, 20, 0, edge, 1)));
                chip.setFocusable(true);
                chip.setClickable(true);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        asker.ask(q);
                    }
                });
                row.addView(chip, margins(d, 0, 0, 10, 0));
            }
            box.addView(row);
        }

        box.addView(text(a, "OK asks  ·  Back closes", gfx.face(Gfx.TEXT_MEDIUM), 13, tertiary), margins(d, 0, 20, 0, 0));

        dialog.setContentView(box);
        int width = Math.min(px(d, 820), a.getResources().getDisplayMetrics().widthPixels - px(d, 80));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams p = w.getAttributes();
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = px(d, 36);
            p.dimAmount = 0.55f;
            w.setAttributes(p);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // The keyboard opens with the box, so speaking or typing is one step away.
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                asker.closed();
            }
        });
        field.requestFocus();
        dialog.show();
        if (w != null) w.setLayout(width, WRAP);
        return sheet;
    }

    private static int px(float d, float dp) {
        return Math.round(dp * d);
    }

    private static TextView text(Context c, String s, Typeface face, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams margins(float d, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.setMargins(px(d, l), px(d, t), px(d, r), px(d, b));
        return p;
    }

    /** A rounded shape: {@code fill} inside and a {@code stroke} dp edge of {@code edge}, either may be clear. */
    private static GradientDrawable pill(float d, float radius, int fill, int edge, float stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(d, radius));
        g.setColor(fill);
        if (stroke > 0) g.setStroke(Math.max(1, px(d, stroke)), edge);
        return g;
    }

    private static StateListDrawable states(GradientDrawable focused, GradientDrawable rest) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[0], rest);
        return s;
    }

    /** The microphone, in the button's ink when it has focus and the accent otherwise. */
    private static final class Glyph extends View {
        private final AndroidGfx gfx;
        private final int on, off;

        Glyph(Context c, AndroidGfx gfx, int on, int off) {
            super(c);
            this.gfx = gfx;
            this.on = on;
            this.off = off;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            gfx.begin(canvas);
            View p = (View) getParent();
            Icons.mic(gfx, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()), p != null && p.isFocused() ? on : off);
        }
    }

    /** The sparkle, drawn by the same code as the home screen's. */
    private static final class Sparkle extends View {
        private final AndroidGfx gfx;
        private final int color;

        Sparkle(Context c, AndroidGfx gfx, int color) {
            super(c);
            this.gfx = gfx;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            gfx.begin(canvas);
            Icons.sparkle(gfx, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()), color);
        }
    }
}
