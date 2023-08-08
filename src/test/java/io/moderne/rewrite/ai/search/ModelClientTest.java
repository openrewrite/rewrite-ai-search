package io.moderne.rewrite.ai.search;

import io.moderne.rewrite.ai.ModelClient;
import org.junit.jupiter.api.Test;

public class ModelClientTest {

    @Test
    void start() {
        ModelClient client = new ModelClient();

        client.start();
    }
}
