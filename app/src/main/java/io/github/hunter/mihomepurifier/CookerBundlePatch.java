package io.github.hunter.mihomepurifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class CookerBundlePatch {
    private static final int MODULE_ID = 10010;
    private static final String INITIAL_STATE = "s.state={currentIndex:0,subtitle:";
    private static final String SCROLL_STYLE = "contentContainerStyle:{width:2*I.CMScreenUtil.width}";

    static boolean matchesPath(String path) {
        // shortcut: 仅适配已验证的 cmwy3 插件版本，插件更新后须重新核对源码。
        return path != null && path.endsWith(
                "/files/plugin/install/rn/1016529/1034887/android/main.bundle");
    }

    static byte[] patch(byte[] bundle) {
        if (bundle.length < 12) {
            throw new IllegalArgumentException("RAM bundle 头不完整");
        }
        ByteBuffer header = ByteBuffer.wrap(bundle).order(ByteOrder.LITTLE_ENDIAN);
        int count = header.getInt(4);
        long base = 12L + count * 8L;
        if (header.getInt(0) != 0xfb0bd1e5 || count <= MODULE_ID || base > bundle.length) {
            throw new IllegalArgumentException("RAM bundle 索引不匹配");
        }
        int entry = 12 + MODULE_ID * 8;
        int offset = header.getInt(entry);
        int length = header.getInt(entry + 4);
        long start = base + offset;
        if (offset < 0 || length <= 0 || start + length > bundle.length
                || bundle[(int) (start + length - 1)] != 0) {
            throw new IllegalArgumentException("首页模块范围无效");
        }
        String module = new String(bundle, (int) start, length - 1, StandardCharsets.UTF_8);
        if (!module.contains("s.titles=[\"\\u98df\\u8c31\",\"\\u8bbe\\u5907\"]")
                || module.indexOf(INITIAL_STATE) < 0
                || module.indexOf(INITIAL_STATE) != module.lastIndexOf(INITIAL_STATE)
                || module.indexOf(SCROLL_STYLE) < 0
                || module.indexOf(SCROLL_STYLE) != module.lastIndexOf(SCROLL_STYLE)) {
            throw new IllegalArgumentException("首页源码特征不匹配");
        }
        byte[] replacement = (module.replace(INITIAL_STATE, "s.state={currentIndex:1,subtitle:")
                // 此版 RN 的 Android ScrollView 不应用初始 contentOffset，内容布局后再定位。
                .replace(SCROLL_STYLE, "onContentSizeChange:function(){t.pageScrollView.scrollTo({"
                        + "x:I.CMScreenUtil.width*t.state.currentIndex,y:0,animated:!1})}," + SCROLL_STYLE)
                + '\0').getBytes(StandardCharsets.UTF_8);
        // 追加新模块，只调整这一项索引，其余模块和启动代码的偏移保持原样。
        byte[] result = Arrays.copyOf(bundle, bundle.length + replacement.length);
        System.arraycopy(replacement, 0, result, bundle.length, replacement.length);
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(entry, (int) (bundle.length - base))
                .putInt(entry + 4, replacement.length);
        return result;
    }
}
