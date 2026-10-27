package tv.vestor.home;

import java.util.Map;

/** Video Calls: the engine check, where the screen may go, who gets the camera, the pointer, the Samsung's report. */
public final class CallingTest {
    private static int failures, checks;

    public static void main(String[] args) {
        // The engine's version, from the user agents WebView and the Samsung report
        eq("WebView", 126, Calling.chromeMajor("Mozilla/5.0 (Linux; Android 11; AFTKM Build/PS7633; wv) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.134 Mobile Safari/537.36"));
        eq("no Chrome in it", 0, Calling.chromeMajor("Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.5) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) 69.0.3497.106.1/5.5 TV Safari/537.36"));
        eq("absurd version capped at four digits", 9999, Calling.chromeMajor("Chrome/99999999"));
        eq("null", 0, Calling.chromeMajor(null));
        eq("desktop name at the engine's version", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36", Calling.desktopAgent(126));
        ok("WhatsApp's floor", Calling.MIN_CHROME == 100);

        // Where the page may go
        ok("WhatsApp Web", Calling.allowed("https://web.whatsapp.com/", true));
        ok("WhatsApp Web with a path", Calling.allowed("https://web.whatsapp.com/send?phone=1", true));
        ok("upper case host", Calling.allowed("https://WEB.WhatsApp.com/", true));
        ok("not plain HTTP", !Calling.allowed("http://web.whatsapp.com/", true));
        ok("not another site", !Calling.allowed("https://example.com/", true));
        ok("not a look-alike", !Calling.allowed("https://web.whatsapp.com.evil.example/", true));
        ok("not user info", !Calling.allowed("https://web.whatsapp.com@evil.example/", true));
        ok("not a port", !Calling.allowed("https://web.whatsapp.com:8443/", true));
        ok("not whatsapp.com itself as the page", !Calling.allowed("https://www.whatsapp.com/", true));
        ok("frames on WhatsApp's domains", Calling.allowed("https://static.whatsapp.net/x", false));
        ok("frames on whatsapp.com", Calling.allowed("https://faq.whatsapp.com/x", false));
        ok("no frames elsewhere", !Calling.allowed("https://evilwhatsapp.net/x", false));
        ok("no javascript:", !Calling.allowed("javascript:alert(1)", true));
        ok("no blob:", !Calling.allowed("blob:https://web.whatsapp.com/x", true));
        ok("no null", !Calling.allowed(null, true));

        // The camera and microphone go to WhatsApp Web only
        ok("WhatsApp gets the camera", Calling.mayCapture("https://web.whatsapp.com/"));
        ok("without the slash", Calling.mayCapture("https://web.whatsapp.com"));
        ok("not over HTTP", !Calling.mayCapture("http://web.whatsapp.com/"));
        ok("not another origin", !Calling.mayCapture("https://evil.example/"));
        ok("not a subdomain trick", !Calling.mayCapture("https://web.whatsapp.com.evil.example/"));
        ok("not null", !Calling.mayCapture(null));

        // The pointer: fine first, faster held, never past a twenty-fourth of the screen
        float w = 1920;
        eq("a single press", 16f, Calling.step(0, w));
        ok("held is faster", Calling.step(10, w) > Calling.step(1, w));
        eq("capped", 80f, Calling.step(1000, w));

        // The Samsung's report, in Vestor's own words
        eq("a webcam", "The Samsung's browser (Chromium 69) sees 1 camera and 1 microphone. The TV can read the webcam, so a Samsung "
                + "camera app for WhatsApp can work.", Calling.camReport(m("{\"engine\":69,\"devices\":true,\"cameras\":1,\"mics\":1}")));
        eq("no camera", "The Samsung's browser (Chromium 69) sees no camera and no microphone. A webcam on the TV's USB port cannot be "
                + "used on this TV.", Calling.camReport(m("{\"engine\":69,\"devices\":true,\"cameras\":0,\"mics\":0}")));
        eq("a microphone only", "The Samsung's browser (Chromium 69) sees no camera but 1 microphone. A webcam on the TV's USB port "
                + "cannot be used on this TV.", Calling.camReport(m("{\"engine\":69,\"devices\":true,\"cameras\":0,\"mics\":1}")));
        eq("no camera interface", "The Samsung's browser (Chromium 69) cannot list cameras at all, so no app on it can use a webcam.",
                Calling.camReport(m("{\"engine\":69,\"devices\":false}")));
        String hostile = Calling.camReport(m("{\"engine\":\"<b>\",\"devices\":true,\"cameras\":99999,\"mics\":-4,\"x\":\"Pwned\"}"));
        ok("numbers clamped, nothing sent shown as text", hostile.contains("9 cameras") && hostile.contains("0 microphones")
                && !hostile.contains("Pwned") && !hostile.contains("<b>"));
        eq("nothing", "", Calling.camReport(null));

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    private static Map<String, Object> m(String json) {
        return Json.obj(Json.parse(json));
    }

    private static void ok(String what, boolean cond) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }
}
