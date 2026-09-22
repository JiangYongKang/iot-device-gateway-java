package com.github.highcumontoa.iotdevicegatewayjava.web.dto;

import java.util.Map;

public record ReportedRequest(String sessionId, Map<String, Object> reported,
                              Map<String, String> rejected) {
}
