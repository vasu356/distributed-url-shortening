import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal liveness probe used as the Docker HEALTHCHECK command.
 * Compiled in the JDK builder stage so the runtime JRE only executes
 * the pre-compiled class — no jdk.compiler module required.
 *
 * <p>Build: {@code javac HealthCheck.java -d /out}
 * <p>Run:   {@code java -cp /out HealthCheck}
 *
 * <p>Exit codes: 0 = healthy, 1 = unhealthy (non-2xx or connection failure).
 */
public class HealthCheck {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8080/actuator/health/liveness"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            System.exit(1);
        }
    }
}
