package tv.vestor.home;

import java.util.Map;

/** Video Calls: the engine check, where the screen may go, who gets the camera, the pointer, the Samsung's report, call audio. */
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

        // Call audio: where the sound and your voice go
        CallAudio.Facts none = new CallAudio.Facts();
        eq("nothing connected: the TV", CallAudio.TV, CallAudio.route(none, CallAudio.HEADSET));
        eq("no mic, no AirPods: told", "No microphone found. For your voice, plug a USB webcam into the Fire TV.",
                CallAudio.line(CallAudio.TV, none, false));
        CallAudio.Facts cam = facts(false, false, true, false);
        eq("webcam alone: the TV, said nothing", "", CallAudio.line(CallAudio.route(cam, CallAudio.HEADSET), cam, false));

        // The likely Fire TV: AirPods for listening only (no hands-free profile)
        CallAudio.Facts ears = facts(false, true, true, true);
        eq("AirPods for sound, webcam for voice", CallAudio.EARS, CallAudio.route(ears, CallAudio.HEADSET));
        eq("said plainly", "Call sound plays in your AirPods and your voice goes through the webcam.",
                CallAudio.line(CallAudio.EARS, ears, false));
        CallAudio.Facts earsNoMic = facts(false, true, false, true);
        eq("AirPods for sound, no webcam", CallAudio.EARS, CallAudio.route(earsNoMic, CallAudio.HEADSET));
        ok("asks for a webcam", CallAudio.line(CallAudio.EARS, earsNoMic, false).contains("listening only")
                && CallAudio.line(CallAudio.EARS, earsNoMic, false).contains("plug a USB webcam"));

        // AirPods whose microphone Fire OS offers
        CallAudio.Facts both = facts(true, true, false, true);
        eq("AirPods both ways", CallAudio.BOTH, CallAudio.route(both, CallAudio.HEADSET));
        eq("said", "Call sound and your voice go through your AirPods.", CallAudio.line(CallAudio.BOTH, both, false));
        CallAudio.Facts bothCam = facts(true, true, true, true);
        eq("AirPods mic wanted over the webcam", CallAudio.BOTH, CallAudio.route(bothCam, CallAudio.HEADSET));
        eq("webcam wanted: AirPods keep full sound", CallAudio.EARS, CallAudio.route(bothCam, CallAudio.WEBCAM));
        eq("webcam wanted but none: the AirPods mic anyway", CallAudio.BOTH, CallAudio.route(both, CallAudio.WEBCAM));
        eq("an unknown setting is the default", CallAudio.BOTH, CallAudio.route(bothCam, "weird"));
        CallAudio.Facts noOffCall = facts(true, true, true, true);
        noOffCall.offCall = false;
        eq("Fire OS keeps the mic for phone calls: listening only", CallAudio.EARS, CallAudio.route(noOffCall, CallAudio.HEADSET));
        CallAudio.Facts hfpOnly = facts(true, false, true, true);
        eq("hands-free only, webcam wanted: the TV plays", CallAudio.TV, CallAudio.route(hfpOnly, CallAudio.WEBCAM));

        // The link tried and failed, or dropped
        eq("failed, webcam", "The microphone in your AirPods did not start. Call sound plays in your AirPods and your voice goes through "
                + "the webcam.", CallAudio.line(CallAudio.EARS, ears, true));
        eq("failed, no webcam", "The microphone in your AirPods did not start. Call sound plays in them. For your voice, plug a USB "
                + "webcam into the Fire TV.", CallAudio.line(CallAudio.EARS, earsNoMic, true));
        eq("dropped to the TV", "Your AirPods disconnected, so call sound plays on the TV.", CallAudio.dropped(CallAudio.TV, ears));
        eq("dropped mic, sound stays", "The microphone in your AirPods stopped. Call sound stays in them and your voice goes through the "
                + "webcam.", CallAudio.dropped(CallAudio.EARS, ears));

        // Names and the check
        ok("AirPods by name", CallAudio.airpods("Vansh\u2019s AirPods Pro") && CallAudio.airpods("AIRPODS MAX"));
        ok("not AirPods", !CallAudio.airpods("WH-1000XM5") && !CallAudio.airpods(null));
        CallAudio.Facts other = facts(false, true, false, false);
        ok("other headphones named generically", CallAudio.line(CallAudio.EARS, other, false).startsWith("Call sound plays in your Bluetooth headphones."));
        eq("check, listening only", "Your AirPods are connected for listening. Fire OS does not offer their microphone, so calls play in "
                + "your AirPods and your voice needs another microphone. A webcam or USB microphone is plugged in.",
                CallAudio.describe(ears, CallAudio.HEADSET));
        eq("check, both ways", "Your AirPods are connected and Fire OS offers their microphone. Calls use your AirPods both ways, at "
                + "phone-call sound quality. No USB microphone is plugged in.", CallAudio.describe(both, CallAudio.HEADSET));
        eq("check, webcam chosen", "Your AirPods are connected and Fire OS offers their microphone. Calls take your voice from the webcam "
                + "and play in your AirPods in full sound. A webcam or USB microphone is plugged in.", CallAudio.describe(bothCam, CallAudio.WEBCAM));
        eq("check, nothing", "No Bluetooth headphones are connected, so calls play on the TV. No USB microphone is plugged in.",
                CallAudio.describe(none, CallAudio.HEADSET));
        for (String t : new String[] {CallAudio.line(CallAudio.EARS, earsNoMic, false), CallAudio.line(CallAudio.EARS, earsNoMic, true),
                CallAudio.describe(noOffCall, CallAudio.HEADSET), CallAudio.describe(bothCam, CallAudio.WEBCAM), CallAudio.dropped(CallAudio.EARS, earsNoMic)}) {
            ok("house style: " + t, t.indexOf(';') < 0 && t.indexOf('\u2014') < 0);
        }

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    private static CallAudio.Facts facts(boolean headset, boolean headphones, boolean mic, boolean airpods) {
        CallAudio.Facts f = new CallAudio.Facts();
        f.headset = headset;
        f.headphones = headphones;
        f.mic = mic;
        f.airpods = airpods;
        return f;
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
