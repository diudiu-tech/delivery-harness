package com.delivery.harness.gateway.platform.operations;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;

@RestController
public class WorkbenchController {
    @GetMapping({"/", "/workbench", "/workbench/"})
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(302).location(URI.create("/workbench/index.html")).build();
    }
}
