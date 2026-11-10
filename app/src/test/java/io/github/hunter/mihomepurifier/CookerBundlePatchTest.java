package io.github.hunter.mihomepurifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class CookerBundlePatchTest {
    private static final int ENTRY = 12 + 10010 * 8;

    public static void main(String[] args) throws Exception {
        String path = "/data/user/0/com.xiaomi.smarthome/files/plugin/install/rn/1016529/1034887/android/main.bundle";
        assert CookerBundlePatch.matchesPath(path);
        assert !CookerBundlePatch.matchesPath(path.replace("1034887", "9999999"));
        assert !CookerBundlePatch.matchesPath(path.replace("1016529", "1005378"));
        assert !CookerBundlePatch.matchesPath(null);

        byte[] module = ("s.titles=[\"\\u98df\\u8c31\",\"\\u8bbe\\u5907\"];"
                + "s.state={currentIndex:0,subtitle:\"\"};"
                + "contentContainerStyle:{width:2*I.CMScreenUtil.width}\0")
                .getBytes(StandardCharsets.UTF_8);
        int base = ENTRY + 8;
        byte[] original = new byte[base + module.length];
        ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0, 0xfb0bd1e5).putInt(4, 10011)
                .putInt(ENTRY, 0).putInt(ENTRY + 4, module.length);
        System.arraycopy(module, 0, original, base, module.length);
        verify(original);
        reject(new byte[11]);
        byte[] broken = original.clone();
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Integer.MAX_VALUE);
        reject(broken);
        broken = original.clone();
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(ENTRY, -1);
        reject(broken);
        broken = original.clone();
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(ENTRY + 4, Integer.MAX_VALUE);
        reject(broken);
        broken = original.clone();
        broken[base] = '?';
        reject(broken);
        broken = original.clone();
        broken[broken.length - 1] = '?';
        reject(broken);
        reject(CookerBundlePatch.patch(original));
        if (args.length > 0) {
            verify(Files.readAllBytes(Path.of(args[0])));
            System.out.println("PASS: 实际电饭煲 RAM bundle 补丁校验");
        }
        System.out.println("PASS: 默认页、内容位置、其他模块保留、版本范围及异常输入校验");
    }

    private static void verify(byte[] original) {
        byte[] snapshot = original.clone();
        byte[] patched = CookerBundlePatch.patch(original);
        assert Arrays.equals(original, snapshot) : "不能修改原始输入";
        ByteBuffer index = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN);
        int start = 12 + index.getInt(4) * 8 + index.getInt(ENTRY);
        int length = index.getInt(ENTRY + 4);
        assert start == original.length;
        assert start + length == patched.length;
        assert patched[patched.length - 1] == 0;
        String module = new String(patched, start, length - 1, StandardCharsets.UTF_8);
        assert module.contains("currentIndex:1,subtitle:");
        assert module.contains("onContentSizeChange:function(){t.pageScrollView.scrollTo({"
                + "x:I.CMScreenUtil.width*t.state.currentIndex,y:0,animated:!1})}");
        assert module.contains("s.titles=[\"\\u98df\\u8c31\",\"\\u8bbe\\u5907\"]");
        for (int i = 0; i < original.length; i++) {
            assert (i >= ENTRY && i < ENTRY + 8) || original[i] == patched[i]
                    : "只能修改首页模块的索引";
        }
    }

    private static void reject(byte[] input) {
        try {
            CookerBundlePatch.patch(input);
            throw new AssertionError("应拒绝不匹配的插件");
        } catch (IllegalArgumentException expected) {
            // 不匹配时由 Hook 回退到原插件。
        }
    }
}
