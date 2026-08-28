package dev.flamelens.fusio.spring;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Minimal host application for the integration tests: no fusio-specific
 * configuration anywhere — the starter's auto-configuration must supply
 * everything, which is exactly the "drop the jar in" claim under test.
 */
@SpringBootApplication
public class StarterTestApplication {

    @RestController
    static class CsvController {

        @PostMapping(value = "/rows", consumes = "text/csv")
        Map<String, Object> importRows(@RequestBody List<String[]> rows) {
            return Map.of(
                    "rowCount", rows.size(),
                    "firstCell", rows.isEmpty() ? "" : rows.get(0)[0],
                    "widestRow", rows.stream().mapToInt(r -> r.length).max().orElse(0));
        }

        @GetMapping(value = "/rows", produces = "text/csv")
        List<String[]> exportRows() {
            return List.of(
                    new String[]{"sku", "description", "count"},
                    new String[]{"A-1", "plain", "3"},
                    new String[]{"B-2", "needs,quoting", "5"},
                    new String[]{"C-3", "say \"hi\"", "7"});
        }

        @PostMapping(value = "/echo", consumes = "application/json")
        Map<String, Object> echoJson(@RequestBody Map<String, Object> body) {
            return body;
        }
    }
}
