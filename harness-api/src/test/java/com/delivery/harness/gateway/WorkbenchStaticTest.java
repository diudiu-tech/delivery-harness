package com.delivery.harness.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(DemoApiTestSupport.class)
@AutoConfigureMockMvc
class WorkbenchStaticTest {

    private static final Pattern INLINE_EVENT_HANDLER = Pattern.compile("\\bon[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_SCRIPT_TAG = Pattern.compile(
            "<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void staticWorkbenchAssetsAreServedWithStrictCspHeaders() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(containsString("Delivery Harness Workbench")));

        mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mockMvc.perform(get("/styles.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void frontendAssetsEnforceZeroInnerHtmlAndZeroInlineScriptXssGuards() throws Exception {
        Path staticDir = Path.of("src/main/resources/static");
        String appJs = Files.readString(staticDir.resolve("app.js"), StandardCharsets.UTF_8);
        String indexHtml = Files.readString(staticDir.resolve("index.html"), StandardCharsets.UTF_8);

        List<String> forbiddenJsSinks = List.of(
                "innerHTML",
                "outerHTML",
                "insertAdjacentHTML",
                "document.write",
                "eval(",
                "new Function");
        for (String sink : forbiddenJsSinks) {
            assertFalse(appJs.contains(sink), "app.js must not use dangerous DOM/JS sink: " + sink);
        }

        assertFalse(INLINE_SCRIPT_TAG.matcher(indexHtml).find(),
                "index.html must not contain inline <script> blocks without src=");
        assertFalse(INLINE_EVENT_HANDLER.matcher(indexHtml).find(),
                "index.html must not contain inline on*= event handlers");
        assertFalse(indexHtml.contains("http://") || indexHtml.contains("https://"),
                "index.html must not reference external CDN origins under default-src 'self'");
        assertTrue(indexHtml.contains("<script type=\"module\" src=\"/app.js\"></script>"),
                "index.html must load /app.js as an external module");
    }
}
