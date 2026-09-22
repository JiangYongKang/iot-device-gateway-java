package com.github.highcumontoa.iotdevicegatewayjava.firmware;

/**
 * 简单语义版本比较：支持 "主.次.补丁" 数字段；非法格式抛 IllegalArgumentException。
 */
public final class VersionCompare {

    private VersionCompare() {
    }

    public static int compare(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("版本号为空");
        }
        String core = version.trim().split("[-+]")[0];
        String[] parts = core.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("\\d+")) {
                throw new IllegalArgumentException("非法版本号: " + version);
            }
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }
}
