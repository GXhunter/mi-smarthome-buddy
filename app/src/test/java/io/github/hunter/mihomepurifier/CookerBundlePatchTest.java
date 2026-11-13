package io.github.hunter.mihomepurifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class CookerBundlePatchTest {
    public static void main(String[] args) throws Exception {
        String path = "/data/user/0/com.xiaomi.smarthome/files/plugin/install/rn/1016529/1034887/android/main.bundle";
        assert CookerBundlePatch.matchesPath(path);
        assert CookerBundlePatch.matchesPath(path.replace("1034887", "9999999"));
        assert CookerBundlePatch.matchesPath(path.replace("1016529", "1005378"));
        assert !CookerBundlePatch.matchesPath(path.replace("1034887", ".."));
        assert !CookerBundlePatch.matchesPath(path.replace("main.bundle", "other.bundle"));
        assert !CookerBundlePatch.matchesPath(null);
        assert CookerBundlePatch.isCookerModel("viomi.cooker.v4");
        assert !CookerBundlePatch.isCookerModel("viomi.clock.v4");

        byte[] module = ("a.titles=[\"食谱\",\"设备\"];"
                + "a.state={currentIndex:0,subtitle:\"\"};"
                + "renderRecipePage;renderDevicePage;renderScrollPage;selectedItemChanged:"
                + "ref:function(k){return b.pageScrollView=k},style:{flex:1},"
                + "contentContainerStyle:{width:2*C.CMScreenUtil.width},pagingEnabled:!0,horizontal:!0\0")
                .getBytes(StandardCharsets.UTF_8);
        byte[] original = bundle(2, module);
        int entry = 12 + 2 * 8;
        int base = entry + 8;
        assert verify(original) == entry;
        assert verify(bundle(7, module)) == 12 + 7 * 8;
        reject(bundle(7, module, module));
        reject(new byte[11]);
        byte[] broken = original.clone();
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Integer.MAX_VALUE);
        reject(broken);
        broken = original.clone();
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(entry, -1);
        reject(broken);
        broken = original.clone();
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(entry + 4, Integer.MAX_VALUE);
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
        System.out.println("PASS: 移动模块索引、变量改名、候选歧义、设备类别及异常输入校验");
    }

    private static byte[] bundle(int moduleIndex, byte[]... modules) {
        int base = 12 + (moduleIndex + modules.length) * 8;
        byte[] bundle = new byte[base + Arrays.stream(modules).mapToInt(a -> a.length).sum()];
        ByteBuffer table = ByteBuffer.wrap(bundle).order(ByteOrder.LITTLE_ENDIAN);
        table.putInt(0xfb0bd1e5).putInt(moduleIndex + modules.length).putInt(0);
        table.position(12 + moduleIndex * 8);
        int offset = 0;
        for (byte[] module : modules) {
            table.putInt(offset).putInt(module.length);
            System.arraycopy(module, 0, bundle, base + offset, module.length);
            offset += module.length;
        }
        return bundle;
    }

    private static int verify(byte[] original) {
        byte[] snapshot = original.clone();
        byte[] patched = CookerBundlePatch.patch(original);
        assert Arrays.equals(original, snapshot) : "不能修改原始输入";
        ByteBuffer index = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN);
        int base = 12 + index.getInt(4) * 8;
        int entry = -1;
        for (int pos = 12; pos < base; pos += 8) {
            if (index.getInt(pos) == original.length - base && index.getInt(pos + 4) > 0) {
                assert entry < 0;
                entry = pos;
            }
        }
        assert entry >= 0;
        int start = base + index.getInt(entry);
        int length = index.getInt(entry + 4);
        assert start == original.length;
        assert start + length == patched.length;
        assert patched[patched.length - 1] == 0;
        String module = new String(patched, start, length - 1, StandardCharsets.UTF_8);
        assert module.contains("currentIndex:1,subtitle:");
        assert module.contains("onContentSizeChange:function(){");
        assert module.contains(".CMScreenUtil.width*");
        assert module.contains(".state.currentIndex,y:0,animated:!1})}");
        for (int i = 0; i < original.length; i++) {
            assert (i >= entry && i < entry + 8) || original[i] == patched[i]
                    : "只能修改首页模块的索引";
        }
        return entry;
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
