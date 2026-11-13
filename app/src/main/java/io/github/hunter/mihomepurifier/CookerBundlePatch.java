package io.github.hunter.mihomepurifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CookerBundlePatch {
    private static final Pattern PLUGIN_PATH = Pattern.compile(
            ".*/files/plugin/install/rn/[0-9]+/[0-9]+/android/main\\.bundle");
    private static final Pattern COOKER_MODEL = Pattern.compile("[a-zA-Z0-9_-]+\\.cooker\\.[a-zA-Z0-9_-]+");
    private static final String IDENTIFIER = "[a-zA-Z_$][a-zA-Z0-9_$]*";
    private static final Pattern TABS = Pattern.compile("(" + IDENTIFIER
            + ")\\.titles=\\[\"(?:食谱|\\\\u98df\\\\u8c31)\",\"(?:设备|\\\\u8bbe\\\\u5907)\"\\]");
    private static final Pattern SCROLL = Pattern.compile("ref:function\\(" + IDENTIFIER
            + "\\)\\{return (" + IDENTIFIER + ")\\.pageScrollView=" + IDENTIFIER
            + "\\},style:\\{flex:1\\},(contentContainerStyle:\\{width:2\\*(" + IDENTIFIER
            + "\\.CMScreenUtil\\.width)\\}),pagingEnabled:!0,horizontal:!0");

    static boolean matchesPath(String path) {
        return path != null && PLUGIN_PATH.matcher(path).matches();
    }

    static boolean isCookerModel(String model) {
        return model != null && COOKER_MODEL.matcher(model).matches();
    }

    static byte[] patch(byte[] bundle) {
        if (bundle.length < 12) {
            throw new IllegalArgumentException("RAM bundle 头不完整");
        }
        ByteBuffer header = ByteBuffer.wrap(bundle).order(ByteOrder.LITTLE_ENDIAN);
        int count = header.getInt(4);
        int startupLength = header.getInt(8);
        long base = 12L + count * 8L;
        if (header.getInt(0) != 0xfb0bd1e5 || count <= 0 || startupLength < 0
                || base + startupLength > bundle.length) {
            throw new IllegalArgumentException("RAM bundle 索引不匹配");
        }
        int entry = -1;
        byte[] replacement = null;
        for (int i = 0; i < count; i++) {
            int candidate = 12 + i * 8;
            int offset = header.getInt(candidate);
            int length = header.getInt(candidate + 4);
            if (length == 0 && offset == 0) {
                continue;
            }
            long start = base + offset;
            if (offset < startupLength || length <= 0 || start + length > bundle.length
                    || bundle[(int) (start + length - 1)] != 0) {
                throw new IllegalArgumentException("RAM bundle 模块范围无效");
            }
            if (length < 200) {
                continue;
            }
            String module = new String(bundle, (int) start, length - 1, StandardCharsets.UTF_8);
            String patched = patchPage(module);
            if (patched != null) {
                if (entry >= 0) {
                    throw new IllegalArgumentException("电饭煲首页模块不唯一");
                }
                entry = candidate;
                replacement = (patched + '\0').getBytes(StandardCharsets.UTF_8);
            }
        }
        if (replacement == null) {
            throw new IllegalArgumentException("首页源码特征不匹配");
        }
        // 追加新模块，只调整这一项索引，其余模块和启动代码的偏移保持原样。
        byte[] result = Arrays.copyOf(bundle, Math.addExact(bundle.length, replacement.length));
        System.arraycopy(replacement, 0, result, bundle.length, replacement.length);
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(entry, (int) (bundle.length - base))
                .putInt(entry + 4, replacement.length);
        return result;
    }

    private static String patchPage(String module) {
        if (!module.contains("renderRecipePage") || !module.contains("renderDevicePage")
                || !module.contains("renderScrollPage") || !module.contains("selectedItemChanged:")
                || module.contains("onContentSizeChange:")) {
            return null;
        }
        Matcher tabs = TABS.matcher(module);
        if (!tabs.find()) {
            return null;
        }
        String state = tabs.group(1) + ".state={currentIndex:";
        int index = module.indexOf(state + "0,subtitle:", tabs.end());
        if (tabs.find() || index < 0 || index != module.indexOf(state) || index != module.lastIndexOf(state)) {
            return null;
        }
        Matcher scroll = SCROLL.matcher(module);
        if (!scroll.find()) {
            return null;
        }
        int insert = scroll.start(2);
        String owner = scroll.group(1);
        String width = scroll.group(3);
        if (scroll.find()) {
            return null;
        }
        // shortcut: 只覆盖已核对的双 Tab 横滑模板，布局变化时需新增有样本的规则。
        StringBuilder result = new StringBuilder(module);
        result.setCharAt(index + state.length(), '1');
        result.insert(insert, "onContentSizeChange:function(){" + owner + ".pageScrollView.scrollTo({x:"
                + width + "*" + owner + ".state.currentIndex,y:0,animated:!1})},");
        return result.toString();
    }
}
