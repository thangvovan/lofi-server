package lofi;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

/**
 * Swallows the exceptions a listener leaving mid-stream produces.
 *
 * For an ordinary endpoint a broken response is worth shouting about. For this
 * one it is the normal way every request ends - the wallpaper changes station,
 * gets covered, or is torn down - and left alone a quiet hour of listening buries
 * any real fault under disconnect noise.
 *
 * Both types are needed, which took two rounds to learn. A disconnect noticed by
 * Spring surfaces as AsyncRequestNotUsableException; one noticed by the socket
 * write itself surfaces as a plain IOException ("An established connection was
 * aborted by the software in your host machine") thrown from Spring's async
 * completion, after the streaming lambda has already returned - so the try/catch
 * inside it never sees that one.
 *
 * Scoped to this controller rather than the whole application: swallowing every
 * IOException everywhere would hide faults that have nothing to do with a
 * listener hanging up.
 */
@RestControllerAdvice(assignableTypes = StreamController.class)
class DisconnectHandler {

    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    @ResponseStatus(HttpStatus.OK)
    void listenerLeft() {
        // Nothing to do: the response is already gone, and StationStream drops the
        // subscriber on its own when the write fails.
    }
}
