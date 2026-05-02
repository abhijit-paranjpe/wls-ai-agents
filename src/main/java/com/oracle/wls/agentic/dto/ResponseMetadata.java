package com.oracle.wls.agentic.dto;

import io.helidon.json.binding.Json;

import java.util.List;

@Json.Entity
public record ResponseMetadata(List<ResponseAction> actions) {
}
