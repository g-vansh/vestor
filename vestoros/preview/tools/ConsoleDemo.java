package tv.vestor.home;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The TV's real control-center server on a computer, for trying the phone page and for the
 * browser test: it serves assets/web on 127.0.0.1 with the sample state in
 * preview/fixtures/state.json, approves a pairing request after a moment (as OK on the remote
 * would), and prints the pairing link and code.
 *
 *   java -cp <classes> tv.vestor.home.ConsoleDemo [port]
 */
public final class ConsoleDemo {
    private ConsoleDemo() {}

    public static void main(String[] args) throws Exception {
        final File root = new File(System.getProperty("vestor.root", "."));
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8686;
        final String[] stored = {null};
        final Keys keys = new Keys(new Keys.Store() {
            @Override
            public String load() {
                return stored[0];
            }

            @Override
            public void save(String json) {
                stored[0] = json;
            }
        });
        final String state = new String(Files.readAllBytes(new File(root, "preview/fixtures/state.json").toPath()),
                StandardCharsets.UTF_8);
        final List<String> log = new ArrayList<>();
        Console.Host host = new Console.Host() {
            @Override
            public byte[] asset(String name) {
                try {
                    return Files.readAllBytes(new File(root, "assets/" + name).toPath());
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public String state(Keys.Device d, String peer) {
                return state;
            }

            @Override
            public String act(Keys.Device d, String action, Map<String, Object> body) throws Http.Refused {
                synchronized (log) {
                    log.add(action);
                }
                System.out.println("action " + action + " " + body.keySet());
                if ("note".equals(action)) {
                    try {
                        Notes.parse(body, d.name, System.currentTimeMillis());
                    } catch (IllegalArgumentException e) {
                        throw new Http.Refused(400, "note");
                    }
                    return "{\"ok\":true,\"id\":\"n9\"}";
                }
                return "{\"ok\":true}";
            }

            @Override
            public String photo(Keys.Device d, byte[] body, String caption, long taken) {
                System.out.println("photo " + body.length + " bytes, caption " + caption);
                return "{\"ok\":true,\"id\":\"0123456789abcdef0123456789abcdef\"}";
            }

            @Override
            public byte[] photoFile(String id, boolean thumb) {
                return null;
            }

            @Override
            public void pairRequest(final Keys.Pending p) {
                System.out.println("pair request from " + p.name + " at " + p.ip + ", match " + p.match + ": allowing in 1.5 s");
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                        keys.decide(p.id, true, System.currentTimeMillis());
                    }
                });
                t.setDaemon(true);
                t.start();
            }

            @Override
            public String name() {
                return "Vestor";
            }
        };
        Console console = new Console(host, keys);
        console.setPort(port);
        console.listenOn(InetAddress.getByName("127.0.0.1"), 8);
        String[] w = keys.open(System.currentTimeMillis());
        String base = console.urls().get(0);
        System.out.println("url " + base);
        System.out.println("pair " + base + "/#k=" + w[0]);
        System.out.println("pin " + w[1]);
        System.out.flush();
        // What the TV would show: wrong codes typed, and signed-out or moved devices.
        Thread events = new Thread(new Runnable() {
            @Override
            public void run() {
                int wrong = 0;
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (keys.wrongCodes() != wrong) {
                        wrong = keys.wrongCodes();
                        System.out.println("wrong codes typed: " + wrong);
                    }
                    for (String e : keys.events()) System.out.println("event " + e);
                }
            }
        });
        events.setDaemon(true);
        events.start();
        Thread.sleep(Long.MAX_VALUE);
    }
}
