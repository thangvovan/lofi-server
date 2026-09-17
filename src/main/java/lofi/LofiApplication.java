package lofi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns a Lofi Girl live radio into WebM/Opus that the wallpaper can play with a
 * bare {@code <audio>} element.
 *
 * Why this exists at all, measured in the real wallpaper rather than assumed:
 *
 *   * Resolving is NOT the reason. The ANDROID InnerTube client answers OK with
 *     no cookies, no visitor id and no PO token, and sends
 *     Access-Control-Allow-Origin for a file:// page. A browser can do that part.
 *   * Reading the audio is. googlevideo sends no Access-Control-Allow-Origin, so
 *     fetch() cannot touch the bytes - which also rules out ffmpeg.wasm, since it
 *     only works on bytes JavaScript already holds. A media element is exempt,
 *     but it cannot play a live HLS playlist either.
 *   * Decoding is. Wallpaper Engine's CEF reports canPlayType "" for both AAC and
 *     H.264, and a live radio serves nothing else. Opus in WebM reports
 *     "probably", and a probe inside the real wallpaper confirmed it: currentTime
 *     advanced 0.00 -> 2.10 -> 9.10 with the buffer growing.
 *
 * @see StationStream for the one-ffmpeg-per-station fan-out
 */
@SpringBootApplication
@EnableScheduling                       // drives the idle reaper in StationRegistry
@EnableConfigurationProperties(LofiProperties.class)
public class LofiApplication {
    public static void main(String[] args) {
        // Registering the wallpaper is a one-off chore, not a server, so it runs
        // and exits instead of dragging Spring up behind it.
        boolean link = java.util.Arrays.asList(args).contains("--link");
        for (String arg : args) {
            if (arg.equals("--install"))   System.exit(Installer.run(false, link));
            if (arg.equals("--uninstall")) System.exit(Installer.run(true, false));
        }
        SpringApplication.run(LofiApplication.class, args);
    }
}
