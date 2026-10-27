package tv.vestor.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Video Calls: WhatsApp Web, full screen, in the Fire TV's own web engine. The remote's arrows
 * move a pointer and OK clicks (the page is made for a mouse), pushing past the top or bottom edge
 * scrolls, Rewind and Fast Forward scroll a page at a time, a click on a text field brings up the
 * Fire TV keyboard, Back is Escape and Back twice leaves, Menu has the rest.
 *
 * The page may reach nothing but WhatsApp ({@link Calling#allowed}) and only WhatsApp Web gets
 * the camera and microphone. The sign-in stays in this app's private storage until "Sign out".
 */
public final class Calls extends Activity implements Hub.Listener {
    private static final int ASK_DEVICES = 7;
    private static final long BACK_TWICE_MS = 1500, POINTER_REST_MS = 4000, STATUS_MS = 9000;

    private Hub hub;
    private FrameLayout root;
    private WebView web;
    private Pointer pointer;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Screen pixels of the WebView per pixel of its layout: the page lays out 1280 wide. */
    private float scale = 1;
    private float px, py;
    private long lastBack, statusUntil;
    private PermissionRequest pending;
    private String[] pendingResources;
    /** The grant waiting for the AirPods' call link (see {@link CallRouter#beforeMic}). */
    private PermissionRequest granting;
    private CallRouter router;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        hub = Hub.get(this);
        // A call is long and quiet on the remote: no screensaver over it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);
        setContentView(root);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        px = w / 2f;
        py = h / 2f;

        status = new TextView(this);
        status.setTextColor(0xFFEEF1FF);
        status.setTextSize(17);
        int pad = Math.round(14 * density);
        status.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141826);
        bg.setCornerRadius(18 * density);
        status.setBackground(bg);
        status.setMaxWidth(Math.round(w * 0.8f));

        int major;
        try {
            major = Calling.chromeMajor(WebSettings.getDefaultUserAgent(this));
        } catch (RuntimeException e) {
            // No web engine (being updated, or switched off): nothing to show WhatsApp in.
            addStatus(w);
            say("The Fire TV's web engine is not available right now. Try again in a minute.", true);
            return;
        }
        if (major < Calling.MIN_CHROME) {
            // WhatsApp would only ask for a newer browser: say so here instead.
            addStatus(w);
            say("This Fire TV's web engine is Chrome " + major + ". WhatsApp needs " + Calling.MIN_CHROME
                    + " or newer. Update the Fire TV in Settings, My Fire TV, About.", true);
            return;
        }
        scale = Math.min(1f, w / (1280f * density));
        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            addStatus(w);
            say("The Fire TV's web engine is not available right now. Try again in a minute.", true);
            return;
        }
        root.addView(web, new FrameLayout.LayoutParams(Math.round(w / scale), Math.round(h / scale)));
        web.setPivotX(0);
        web.setPivotY(0);
        web.setScaleX(scale);
        web.setScaleY(scale);
        // Keys come to the activity first (the pointer), never to the page's own focus.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setUserAgentString(Calling.desktopAgent(major));
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (Calling.allowed(url, request.isForMainFrame())) return false;
                if (request.isForMainFrame()) say("Links open on your phone. This screen shows WhatsApp only.", false);
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                say("WhatsApp's certificate did not check out, so nothing was loaded.", true);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page's process ran out of memory or crashed: leave cleanly rather than take Vestor with it.
                if (web == view) {
                    root.removeView(web);
                    web.destroy();
                    web = null;
                }
                finish();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        answer(request);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pending == request) pending = null;
                if (granting == request) granting = null;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                say("Sending files works from your phone.", false);
                return false;
            }
        });

        pointer = new Pointer(this, density);
        root.addView(pointer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addStatus(w);
        web.loadUrl(Calling.HOME);
        web.requestFocus();
        say("Arrows move the pointer, OK clicks. Menu has the AirPods and camera checks. Back twice leaves.", false);
        router = new CallRouter(this, hub.prefs, main, new CallRouter.Out() {
            @Override
            public void say(String text) {
                Calls.this.say(text, false);
            }
        });
        hub.listen(this);
    }

    private void addStatus(int w) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(w * 0.03f);
        root.addView(status, lp);
        status.setVisibility(View.GONE);
    }

    /** A line at the bottom of the screen for a few seconds (or until the next one when it is an error). */
    private void say(String text, boolean stay) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        statusUntil = stay ? Long.MAX_VALUE : SystemClock.uptimeMillis() + STATUS_MS;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (SystemClock.uptimeMillis() >= statusUntil) status.setVisibility(View.GONE);
            }
        }, STATUS_MS + 50);
    }

    @Override
    public void hub(int what, Object detail) {
        if (what == Hub.CAMCHECK && detail instanceof String) say((String) detail, true);
    }

    // ---- Camera and microphone -------------------------------------------------------------------

    /** WhatsApp Web asks for the camera or microphone: granted for WhatsApp only, after Android's own prompt. */
    private void answer(PermissionRequest request) {
        if (!Calling.mayCapture(request.getOrigin().toString())) {
            request.deny();
            return;
        }
        List<String> take = new ArrayList<>(), need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                take.add(r);
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                take.add(r);
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    need.add(Manifest.permission.RECORD_AUDIO);
                }
            }
        }
        if (take.isEmpty()) {
            request.deny();
            return;
        }
        if (need.isEmpty()) {
            grant(request, take.toArray(new String[0]));
            return;
        }
        if (pending != null) pending.deny();
        pending = request;
        pendingResources = take.toArray(new String[0]);
        requestPermissions(need.toArray(new String[0]), ASK_DEVICES);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_DEVICES || pending == null) return;
        List<String> ok = new ArrayList<>();
        for (String r : pendingResources) {
            String p = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) ok.add(r);
        }
        if (ok.isEmpty()) pending.deny();
        else grant(pending, ok.toArray(new String[0]));
        pending = null;
        pendingResources = null;
    }

    /**
     * Grants WhatsApp what Android allowed. With the microphone in it, the call's sound and voice
     * are routed first (the AirPods' call link has to be up before the microphone opens).
     */
    private void grant(final PermissionRequest request, final String[] resources) {
        boolean mic = false;
        for (String r : resources) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) mic = true;
        if (!mic || router == null) {
            request.grant(resources);
            return;
        }
        granting = request;
        router.beforeMic(new Runnable() {
            @Override
            public void run() {
                if (granting != request) return;
                granting = null;
                request.grant(resources);
            }
        });
    }

    /** What the Fire TV itself offers a call: cameras Android can open, microphones, and USB devices of either kind. */
    static String fireTvDevices(Context c) {
        int cams = 0, mics = 0, usbVideo = 0, usbAudio = 0;
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) cams = cm.getCameraIdList().length;
        } catch (CameraAccessException | RuntimeException e) {
            // no camera service on this device
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC || t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    mics++;
                }
            }
        }
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        if (um != null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                boolean video = false, audio = false;
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    UsbInterface f = d.getInterface(i);
                    if (f.getInterfaceClass() == 14) video = true;
                    if (f.getInterfaceClass() == 1) audio = true;
                }
                if (video) usbVideo++;
                if (audio) usbAudio++;
            }
        }
        StringBuilder b = new StringBuilder("On the Fire TV: ");
        b.append(cams == 0 ? "no camera Android can open" : cams + (cams == 1 ? " camera" : " cameras")).append(", ");
        b.append(mics == 0 ? "no microphone" : mics + (mics == 1 ? " microphone" : " microphones")).append(".");
        if (usbVideo > 0 && cams == 0) b.append(" A USB webcam is plugged in, but Fire OS does not offer it as a camera.");
        else if (usbVideo == 0 && usbAudio == 0) b.append(" No USB webcam is plugged into the Fire TV.");
        return b.toString();
    }

    // ---- The pointer -----------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(code, e.getRepeatCount());
                return true;
            // The remote's select button clicks. Enter is left to the page: the Fire TV keyboard
            // sends it to send a message.
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // The click goes on release, whole: holding OK never turns into a long press.
                if (!down) click();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) scroll(6);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) scroll(-6);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) back();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down) menu();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int code, int repeat) {
        float w = root.getWidth(), h = root.getHeight(), step = Calling.step(repeat, w), edge = h * 0.04f;
        if (code == KeyEvent.KEYCODE_DPAD_UP && py <= edge) {
            scroll(2);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && py >= h - edge) {
            scroll(-2);
        } else {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) px -= step;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) px += step;
            if (code == KeyEvent.KEYCODE_DPAD_UP) py -= step;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) py += step;
            px = Math.max(0, Math.min(w - 1, px));
            py = Math.max(0, Math.min(h - 1, py));
            mouse(MotionEvent.ACTION_HOVER_MOVE);
        }
        pointer.at(px, py);
    }

    /**
     * A click at the pointer, as a tap: WebView's most dependable way to click. The mouse moves
     * that came before it still give the page its hover menus.
     */
    private void click() {
        pointer.at(px, py);
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px / scale, py / scale, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(down);
        down.recycle();
        MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, px / scale, py / scale, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(up);
        up.recycle();
        offerKeyboard();
    }

    /** A mouse event at the pointer, in the WebView's own (unscaled) pixels. */
    private void mouse(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = px / scale;
        coords[0].y = py / scale;
        MotionEvent ev = MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    /** Turns the mouse wheel at the pointer: positive scrolls up. */
    private void scroll(float notches) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = px / scale;
        coords[0].y = py / scale;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, notches);
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(ev);
        ev.recycle();
        pointer.at(px, py);
    }

    /** After a click, brings up the Fire TV keyboard when the click landed in a text field. */
    private void offerKeyboard() {
        web.evaluateJavascript("(function(){var a=document.activeElement;return !!a&&(a.isContentEditable||"
                + "a.tagName==='INPUT'||a.tagName==='TEXTAREA');})()", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"true".equals(value) || web == null) return;
                        web.requestFocus();
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
                    }
                });
    }

    private void back() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < BACK_TWICE_MS) {
            finish();
            return;
        }
        lastBack = now;
        // Escape closes WhatsApp's panels and menus, as on a computer.
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
        say("Press Back again to leave WhatsApp.", false);
    }

    private void menu() {
        final boolean webcamVoice = CallAudio.WEBCAM.equals(hub.prefs.choice(Prefs.CALL_VOICE));
        final String[] items = {"Check the AirPods and microphones",
                webcamVoice ? "Talk through the AirPods when they offer a microphone" : "Talk through the webcam, keep AirPods for listening",
                "Check for a webcam on the Fire TV", "Check for a webcam on the Samsung", "Reload WhatsApp",
                "Sign out of WhatsApp on this TV", "Leave"};
        new AlertDialog.Builder(this).setTitle("Video Calls").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                switch (which) {
                    case 0:
                        say(router.check(), true);
                        break;
                    case 1:
                        hub.prefs.put(Prefs.CALL_VOICE, webcamVoice ? CallAudio.HEADSET : CallAudio.WEBCAM);
                        say(webcamVoice ? "From the next call, your voice goes through the AirPods when Fire OS offers their microphone."
                                : "From the next call, your voice goes through the webcam and the AirPods stay in full sound.", false);
                        break;
                    case 2:
                        say(fireTvDevices(Calls.this), true);
                        break;
                    case 3:
                        say(hub.startCamCheck(System.currentTimeMillis()), true);
                        break;
                    case 4:
                        web.reload();
                        break;
                    case 5:
                        signOut();
                        break;
                    default:
                        finish();
                }
            }
        }).show();
    }

    /** Forgets this TV's WhatsApp sign-in, messages and cache. The phone's WhatsApp is untouched. */
    private void signOut() {
        new AlertDialog.Builder(this).setTitle("Sign out of WhatsApp on this TV?")
                .setMessage("This TV forgets the WhatsApp link. Your phone keeps everything. To use it again, scan the code with your phone.")
                .setPositiveButton("Sign out", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        WebStorage.getInstance().deleteAllData();
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        web.clearCache(true);
                        web.clearHistory();
                        web.loadUrl(Calling.HOME);
                        say("Signed out. On your phone, WhatsApp, Linked devices also lists this TV until it notices.", false);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onDestroy() {
        hub.unlisten(this);
        if (pending != null) pending.deny();
        granting = null;
        if (router != null) router.close();
        if (web != null) {
            CookieManager.getInstance().flush();
            web.stopLoading();
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** The arrow, drawn over the page; it fades when the remote rests. */
    private static final class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float x, y;
        private long movedAt;

        Pointer(Context c, float density) {
            super(c);
            setFocusable(false);
            setClickable(false);
            fill.setColor(Color.WHITE);
            edge.setColor(0xCC000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * density);
            edge.setStrokeJoin(Paint.Join.ROUND);
            float u = 1.6f * density;
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 17 * u);
            arrow.lineTo(4.5f * u, 13 * u);
            arrow.lineTo(7.5f * u, 19.5f * u);
            arrow.lineTo(10 * u, 18.5f * u);
            arrow.lineTo(7 * u, 12 * u);
            arrow.lineTo(12.5f * u, 12 * u);
            arrow.close();
        }

        void at(float nx, float ny) {
            x = nx;
            y = ny;
            movedAt = SystemClock.uptimeMillis();
            setAlpha(1f);
            invalidate();
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (SystemClock.uptimeMillis() - movedAt >= POINTER_REST_MS) animate().alpha(0f).setDuration(400).start();
                }
            }, POINTER_REST_MS + 20);
        }

        @Override
        protected void onDraw(Canvas c) {
            int save = c.save();
            c.translate(x, y);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
            c.restoreToCount(save);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }
    }
}
