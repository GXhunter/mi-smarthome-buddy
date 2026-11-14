package io.github.hunter.mihomepurifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

public final class MaimaiDexTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("maimai-dex-test-");
        Path empty = dir.resolve("empty.apk");
        Path cache = dir.resolve("ad-hunter-maimai-targets");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(empty))) {
                zip.finish();
            }
            var missing = MaimaiDex.findCached(empty.toString(), null, dir);
            assert missing.cold() == null && missing.hot() == null : missing;
            for (String apk : args) {
                var targets = MaimaiDex.find(apk, null);
                assert targets.cold() != null && targets.hot() != null : targets;
                assert targets.equals(MaimaiDex.findCached(apk, null, dir));
                assert targets.equals(MaimaiDex.findCached(apk, null, dir));
                Files.write(cache, new byte[]{1, 2, 3});
                assert targets.equals(MaimaiDex.findCached(apk, null, dir));
                System.out.println("PASS: " + apk + " " + targets);
            }
            assert missing.equals(MaimaiDex.findCached(empty.toString(), null, dir));
            System.out.println("PASS: 脉脉 DEX 缺失目标、缓存命中和失效");
        } finally {
            Files.deleteIfExists(cache);
            Files.deleteIfExists(empty);
            Files.deleteIfExists(dir);
        }
    }
}
