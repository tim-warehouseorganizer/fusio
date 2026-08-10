package dev.flamelens.fusio.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FusioStarterIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void autoConfigurationRegistersTheConverterBean() {
        assertNotNull(context.getBean(FusioCsvHttpMessageConverter.class));
    }

    @Test
    void postCsvBodyBindsToRowList() throws Exception {
        String csv = "sku,qty\n\"A,1\",3\nB-2,5\n";
        mvc.perform(post("/rows").contentType("text/csv").content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.firstCell").value("sku"))
                .andExpect(jsonPath("$.widestRow").value(2));
    }

    @Test
    void quotedMultilineCsvSurvivesTheConverter() throws Exception {
        String csv = "note\n\"line one\nline two\"\n";
        mvc.perform(post("/rows").contentType("text/csv").content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2));
    }

    @Test
    void getProducesRfc4180QuotedCsv() throws Exception {
        mvc.perform(get("/rows").accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(
                        "sku,description,count\n"
                                + "A-1,plain,3\n"
                                + "B-2,\"needs,quoting\",5\n"
                                + "C-3,\"say \"\"hi\"\"\",7\n"));
    }

    /** The filter in action: gzipped JSON body reaches Jackson as plaintext. */
    @Test
    void gzipRequestBodyIsTransparentlyDecompressed() throws Exception {
        String json = "{\"warehouse\":\"north\",\"bays\":42}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        mvc.perform(post("/echo")
                        .contentType("application/json")
                        .header("Content-Encoding", "gzip")
                        .content(bos.toByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouse").value("north"))
                .andExpect(jsonPath("$.bays").value(42));
    }

    /** And the combination: a gzipped CSV upload hits both features at once. */
    @Test
    void gzippedCsvUploadDecompressesThenParses() throws Exception {
        String csv = "sku,qty\nA-1,3\n";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(csv.getBytes(StandardCharsets.UTF_8));
        }
        mvc.perform(post("/rows")
                        .contentType("text/csv")
                        .header("Content-Encoding", "gzip")
                        .content(bos.toByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.firstCell").value("sku"));
    }
}
