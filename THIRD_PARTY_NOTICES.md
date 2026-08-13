# Third-party notices

Delivery Harness source code is licensed under MIT. The build resolves third-party dependencies that retain their own licenses. Important direct components include:

| Component | Use | License |
| --- | --- | --- |
| [Spring Boot](https://github.com/spring-projects/spring-boot) | Application framework and managed dependencies | Apache License 2.0 |
| [Jackson](https://github.com/FasterXML/jackson) | JSON serialization | Apache License 2.0 |
| [Project Lombok](https://github.com/projectlombok/lombok) | Compile-time source generation | MIT License |
| [Ollama](https://github.com/ollama/ollama) | Optional local model runtime | MIT License |

The default `qwen2.5:7b` model is downloaded separately by the user. Model weights are not included in this repository and are not covered by this project's MIT License. Review the model card and license before use.

This file is informational and is not a substitute for reviewing the complete resolved dependency tree and all transitive license files before redistributing a binary or container image.
