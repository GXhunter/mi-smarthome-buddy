package io.github.hunter.mihomepurifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MiHomeDexTest {
    public static void main(String[] args) throws IOException {
        Path directory = Files.createTempDirectory("ad-hunter-dex-test-");
        Path cache = directory.resolve("ad-hunter-mihome-targets");
        Path apk = directory.resolve("test.apk");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk))) {
                output.putNextEntry(new ZipEntry("assets/test.dex"));
                output.write(new byte[]{1});
            }
            var empty = new MiHomeDex.Targets(null, null, null);
            assert empty.equals(MiHomeDex.findCached(apk.toString(), null, directory));
            for (String sample : args) {
                var actual = MiHomeDex.findCached(sample, null, directory);
                assert actual.policy() != null && actual.adPrepared() != null && actual.consent() != null;
                assert actual.equals(MiHomeDex.findCached(sample, null, directory));
                Files.write(cache, new byte[]{1});
                assert actual.equals(MiHomeDex.findCached(sample, null, directory));
                assert empty.equals(MiHomeDex.findCached(sample, new String[]{sample}, directory));
                assert actual.equals(MiHomeDex.findCached(sample, null, directory));
                System.out.println("PASS: " + actual);
            }
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk))) {
                output.putNextEntry(new ZipEntry("classes.dex"));
                output.write(new byte[112]);
            }
            try {
                MiHomeDex.findCached(apk.toString(), null, directory);
                throw new AssertionError("应拒绝不支持的 DEX");
            } catch (IOException expected) {
                // 无法完整检查 APK 时不能安装广告 Hook。
            }
            System.out.println("PASS: DEX 格式、缺失目标及缓存失效校验");
        } finally {
            Files.deleteIfExists(cache);
            Files.deleteIfExists(apk);
            Files.delete(directory);
        }
    }
}
