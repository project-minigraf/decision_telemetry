package io.github.adityamukho.trail.minigraf;

import io.github.adityamukho.trail.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Test double that drives the real Minigraf engine through its Python binding
 * ({@code pip install minigraf}). The Python and JVM bindings wrap the same Rust FFI crate, so the
 * Datalog and JSON seen here are what {@code minigraf-jvm} sees.
 *
 * <p>Use with {@code -Dtrail.minigraf.engineFactory=io.github.adityamukho.trail.minigraf.PythonBridgeEngine}.
 */
public final class PythonBridgeEngine implements DatalogEngine {
    private static final String SCRIPT = String.join("\n",
            "import sys, json, minigraf",
            "p = sys.argv[1]",
            "db = minigraf.MiniGrafDb.open_in_memory() if p == '' else minigraf.MiniGrafDb.open(p)",
            "for line in sys.stdin:",
            "    req = json.loads(line)",
            "    try:",
            "        if req['op'] == 'exec': out = db.execute(req['cmd'])",
            "        elif req['op'] == 'checkpoint': db.checkpoint(); out = '{\"ok\":true}'",
            "        else: break",
            "    except Exception as e:",
            "        out = json.dumps({'error': str(e)})",
            "    sys.stdout.write(json.dumps({'out': out}) + '\\n'); sys.stdout.flush()",
            "db.checkpoint()");

    private final Process process;
    private final BufferedWriter in;
    private final BufferedReader out;

    private PythonBridgeEngine(String path) throws IOException {
        process = new ProcessBuilder("python3", "-c", SCRIPT, path == null ? "" : path)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    public static DatalogEngine open(String path) {
        try {
            return new PythonBridgeEngine(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start python3 minigraf bridge", e);
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized String call(String op, String cmd) {
        try {
            in.write(Json.write(Map.of("op", op, "cmd", cmd == null ? "" : cmd)));
            in.newLine();
            in.flush();
            String line = out.readLine();
            if (line == null) {
                throw new IllegalStateException("minigraf bridge exited");
            }
            return (String) ((Map<String, Object>) Json.parse(line)).get("out");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String execute(String datalog) {
        return call("exec", datalog);
    }

    @Override
    public void checkpoint() {
        call("checkpoint", null);
    }

    @Override
    public void close() {
        try {
            in.write("{\"op\":\"close\"}\n");
            in.flush();
            in.close();
            process.waitFor();
        } catch (IOException e) {
            process.destroy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }
}
