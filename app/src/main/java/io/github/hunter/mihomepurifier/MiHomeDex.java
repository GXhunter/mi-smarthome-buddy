package io.github.hunter.mihomepurifier;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

final class MiHomeDex {
    private static final String[] MARKERS = {
            "bottomSwitch", "screenLoadSwitch", "bannerLoadSwitch", "contentDisplaySwitch",
            "startSwitch", "personalCenterSwitch", "resourceLoadTime", "sh_advertisement",
            "receive AdPrepare ", "prefs_disclaim", "pref_key_smarthome_disclaim_confirmed"
    };
    private static final int POLICY = 0xff;
    private static final int AD_PREPARED = 1 << 8;
    private static final int CONSENT = (1 << 9) | (1 << 10);
    private static final int MAX_DEX_BYTES = 32 * 1024 * 1024;
    private static final Pattern DEX_ENTRY = Pattern.compile("classes(?:[0-9]+)?\\.dex");
    // 定位条件、签名或缓存格式变化时递增。
    private static final int CACHE_SCHEMA = 2;

    record MethodId(String owner, String name) {}
    record Targets(MethodId policy, MethodId adPrepared, MethodId consent) {}

    static Targets findCached(String baseApk, String[] splits, Path directory) throws IOException {
        Path cache = directory.resolve("ad-hunter-mihome-targets");
        byte[] identity = identity(baseApk, splits);
        try {
            if (Files.size(cache) <= 4096) {
                try (DataInputStream input = new DataInputStream(Files.newInputStream(cache))) {
                    int schema = input.readInt();
                    byte[] cachedIdentity = new byte[identity.length];
                    input.readFully(cachedIdentity);
                    if (schema == CACHE_SCHEMA && MessageDigest.isEqual(identity, cachedIdentity)) {
                        Targets targets = new Targets(readTarget(input), readTarget(input), readTarget(input));
                        if (input.read() == -1) {
                            return targets;
                        }
                    }
                }
            }
        } catch (IOException | SecurityException ignored) {
            // 缓存缺失或损坏时重新定位。
        }
        Targets targets = find(baseApk, splits);
        try {
            Path temporary = Files.createTempFile(directory, "ad-hunter-targets-", ".tmp");
            try {
                try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                    output.writeInt(CACHE_SCHEMA);
                    output.write(identity);
                    for (MethodId target : new MethodId[]{targets.policy(), targets.adPrepared(), targets.consent()}) {
                        output.writeBoolean(target != null);
                        if (target != null) {
                            output.writeUTF(target.owner());
                            output.writeUTF(target.name());
                        }
                    }
                }
                Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | SecurityException ignored) {
            // 当前进程仍可使用已解析的目标。
        }
        return targets;
    }

    private static MethodId readTarget(DataInputStream input) throws IOException {
        return input.readBoolean() ? new MethodId(input.readUTF(), input.readUTF()) : null;
    }

    static byte[] identity(String baseApk, String[] splits) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprint(baseApk, digest);
            if (splits != null) {
                for (String split : splits) {
                    fingerprint(split, digest);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fingerprint(String path, MessageDigest digest) throws IOException {
        File file = new File(path);
        try (ZipFile apk = new ZipFile(file)) {
            digest.update((path + '\n' + file.length() + ':' + file.lastModified() + '\n')
                    .getBytes(StandardCharsets.UTF_8));
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (DEX_ENTRY.matcher(entry.getName()).matches()) {
                    digest.update((entry.getName() + ':' + entry.getSize() + ':' + entry.getCrc() + '\n')
                            .getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
    }

    static Targets find(String baseApk, String[] splits) throws IOException {
        Map<Integer, MethodId> targets = new HashMap<>();
        scanApk(baseApk, targets);
        if (splits != null) {
            for (String split : splits) {
                scanApk(split, targets);
            }
        }
        return new Targets(targets.get(POLICY), targets.get(AD_PREPARED), targets.get(CONSENT));
    }

    private static void scanApk(String path, Map<Integer, MethodId> targets) throws IOException {
        forEachDex(path, dex -> dex.scan(targets));
    }

    @FunctionalInterface
    interface DexVisitor {
        void visit(Dex dex) throws IOException;
    }

    static void forEachDex(String path, DexVisitor visitor) throws IOException {
        try (ZipFile apk = new ZipFile(path)) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!DEX_ENTRY.matcher(entry.getName()).matches()) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 112 || size > MAX_DEX_BYTES) {
                    throw new IOException("DEX 大小超出支持范围");
                }
                byte[] bytes = new byte[(int) size];
                try (InputStream input = apk.getInputStream(entry)) {
                    int position = 0;
                    while (position < bytes.length) {
                        int read = input.read(bytes, position, bytes.length - position);
                        if (read < 0) {
                            throw new IOException("DEX 提前结束");
                        }
                        position += read;
                    }
                    if (input.read() != -1) {
                        throw new IOException("DEX 大小不一致");
                    }
                }
                try {
                    visitor.visit(new Dex(bytes));
                } catch (IndexOutOfBoundsException | IllegalArgumentException | ArithmeticException e) {
                    throw new IOException("DEX 表或指令越界", e);
                }
            }
        }
    }

    static final class Dex {
        private final ByteBuffer mData;
        private final int mStrings;
        private final int mTypes;
        private final int mPrototypes;
        private final int mMethods;
        private final int mStringType;
        private final int mBooleanType;
        private final int mVoidType;
        private final Map<Integer, Integer> mMarkers = new HashMap<>();

        Dex(byte[] bytes) throws IOException {
            mData = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            String version = new String(bytes, 4, 3, StandardCharsets.US_ASCII);
            // 容器格式 041 使用不同的偏移语义，整次定位失败，避免漏掉重复候选。
            if (mData.getInt(0) != 0x0a786564 || !version.matches("03[5-9]|040")
                    || bytes[7] != 0 || mData.getInt(32) != bytes.length
                    || mData.getInt(36) != 112 || mData.getInt(40) != 0x12345678) {
                throw new IOException("不支持的 DEX 格式");
            }
            mStrings = mData.getInt(60);
            mTypes = mData.getInt(68);
            mPrototypes = mData.getInt(76);
            mMethods = mData.getInt(92);
            mStringType = findString("Ljava/lang/String;");
            mBooleanType = findString("Z");
            mVoidType = findString("V");
        }

        record Ref(String owner, String name, String result, java.util.List<String> parameters) {}
        record Body(java.util.Set<Ref> calls, java.util.Set<Ref> interfaceCalls,
                    java.util.Set<String> strings) {}

        @FunctionalInterface
        interface MethodVisitor {
            void visit(int flags, Ref method, int code) throws IOException;
        }

        void visitMethods(String prefix, MethodVisitor visitor) throws IOException {
            int classes = mData.getInt(100);
            int count = mData.getInt(96);
            for (int i = 0; i < count; i++) {
                int entry = classes + i * 32;
                String owner = string(typeString(mData.getInt(entry)));
                int data = mData.getInt(entry + 24);
                if (data == 0 || !owner.startsWith(prefix)) {
                    continue;
                }
                mData.position(data);
                int fields = Math.addExact(uleb(), uleb());
                int direct = uleb();
                int virtual = uleb();
                for (int j = 0; j < fields; j++) {
                    uleb();
                    uleb();
                }
                visitMethodList(direct, visitor);
                visitMethodList(virtual, visitor);
            }
        }

        private void visitMethodList(int count, MethodVisitor visitor) throws IOException {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index = Math.addExact(index, uleb());
                int flags = uleb();
                int code = uleb();
                if (code != 0) {
                    visitor.visit(flags, ref(index), code);
                }
            }
        }

        private Ref ref(int index) throws IOException {
            if (index < 0 || index >= mData.getInt(88)) {
                throw new IOException("DEX 方法引用越界");
            }
            int method = mMethods + index * 8;
            int proto = mPrototypes + ushort(method + 2) * 12;
            int list = mData.getInt(proto + 8);
            int count = list == 0 ? 0 : mData.getInt(list);
            if (count < 0 || count > 65535 || (list != 0 && list + 4L + 2L * count > mData.limit())) {
                throw new IOException("DEX 方法参数范围无效");
            }
            var parameters = new java.util.ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                parameters.add(string(typeString(ushort(list + 4 + 2 * i))));
            }
            return new Ref(string(typeString(ushort(method))), string(mData.getInt(method + 4)),
                    string(typeString(mData.getInt(proto + 4))), java.util.List.copyOf(parameters));
        }

        Body body(int code, java.util.Set<String> markers) throws IOException {
            var calls = new java.util.HashSet<Ref>();
            var interfaceCalls = new java.util.HashSet<Ref>();
            var strings = new java.util.HashSet<String>();
            int start = Math.addExact(code, 16);
            int end = Math.addExact(start, Math.multiplyExact(mData.getInt(code + 12), 2));
            if (end < start || end > mData.limit()) {
                throw new IOException("DEX 指令范围无效");
            }
            for (int at = start; at < end;) {
                int instruction = ushort(at);
                int op = instruction & 0xff;
                int width = instructionWidth(at, instruction, op);
                if (width <= 0 || width > (end - at) / 2) {
                    throw new IOException("DEX 指令长度无效");
                }
                if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                    Ref target = ref(ushort(at + 2));
                    calls.add(target);
                    if (op == 0x72 || op == 0x78) {
                        interfaceCalls.add(target);
                    }
                } else if (op == 0x1a || op == 0x1b) {
                    String value = string(op == 0x1a ? ushort(at + 2) : mData.getInt(at + 2));
                    if (markers.contains(value)) {
                        strings.add(value);
                    }
                }
                at += 2 * width;
            }
            return new Body(calls, interfaceCalls, strings);
        }

        void scan(Map<Integer, MethodId> targets) throws IOException {
            int available = 0;
            for (int i = 0; i < MARKERS.length; i++) {
                int index = findString(MARKERS[i]);
                if (index >= 0) {
                    available |= 1 << i;
                    mMarkers.put(index, 1 << i);
                }
            }
            if ((available & POLICY) != POLICY && (available & AD_PREPARED) == 0
                    && (available & CONSENT) != CONSENT) {
                return;
            }
            int classOffset = mData.getInt(100);
            int classCount = mData.getInt(96);
            for (int i = 0; i < classCount; i++) {
                int dataOffset = mData.getInt(classOffset + i * 32 + 24);
                if (dataOffset == 0) {
                    continue;
                }
                mData.position(dataOffset);
                int fields = Math.addExact(uleb(), uleb());
                int direct = uleb();
                int virtual = uleb();
                for (int field = 0; field < fields; field++) {
                    uleb();
                    uleb();
                }
                scanMethods(direct, available, targets);
                scanMethods(virtual, available, targets);
            }
        }

        private void scanMethods(int count, int available, Map<Integer, MethodId> targets) throws IOException {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index = Math.addExact(index, uleb());
                boolean isStatic = (uleb() & 0x8) != 0;
                int code = uleb();
                if (code == 0) {
                    continue;
                }
                int method = mMethods + index * 8;
                int owner = ushort(method);
                int proto = mPrototypes + ushort(method + 2) * 12;
                int result = mData.getInt(proto + 4);
                int parameters = mData.getInt(proto + 8);
                int paramCount = parameters == 0 ? 0 : mData.getInt(parameters);
                int marker = 0;
                if (isStatic && result == owner && paramCount == 1
                        && mStringType == typeString(ushort(parameters + 4))) {
                    marker = POLICY;
                } else if (!isStatic && paramCount == 1 && mVoidType == typeString(result)
                        && mBooleanType == typeString(ushort(parameters + 4))) {
                    marker = AD_PREPARED;
                } else if (isStatic && paramCount == 0 && mBooleanType == typeString(result)) {
                    marker = CONSENT;
                }
                if (marker != 0 && (available & marker) == marker && references(code, marker)) {
                    String descriptor = string(typeString(owner));
                    MethodId candidate = new MethodId(
                            descriptor.substring(1, descriptor.length() - 1).replace('/', '.'),
                            string(mData.getInt(method + 4)));
                    if (targets.containsKey(marker)) {
                        targets.put(marker, null);
                    } else {
                        targets.put(marker, candidate);
                    }
                }
            }
        }

        private boolean references(int code, int required) throws IOException {
            int remaining = required;
            int start = Math.addExact(code, 16);
            int end = Math.addExact(start, Math.multiplyExact(mData.getInt(code + 12), 2));
            if (end < start || end > mData.limit()) {
                throw new IOException("DEX 指令范围无效");
            }
            for (int at = start; at < end;) {
                int instruction = ushort(at);
                int opcode = instruction & 0xff;
                int width = instructionWidth(at, instruction, opcode);
                if (width <= 0 || width > (end - at) / 2) {
                    throw new IOException("DEX 指令长度无效");
                }
                if (opcode == 0x1a || opcode == 0x1b) {
                    int id = opcode == 0x1a ? ushort(at + 2) : mData.getInt(at + 2);
                    remaining &= ~mMarkers.getOrDefault(id, 0);
                }
                at += 2 * width;
            }
            return remaining == 0;
        }

        private int instructionWidth(int at, int instruction, int opcode) throws IOException {
            if (opcode == 0) {
                return switch (instruction) {
                    case 0 -> 1;
                    case 0x100 -> 4 + 2 * ushort(at + 2);
                    case 0x200 -> 2 + 4 * ushort(at + 2);
                    case 0x300 -> Math.toIntExact(4 + (1L + ushort(at + 2)
                            * Integer.toUnsignedLong(mData.getInt(at + 4))) / 2);
                    default -> throw new IOException("未知 DEX 指令数据");
                };
            }
            // Dalvik 指令长度以 16 位码元计，必须跳过操作数与 switch/array 数据。
            return switch (opcode) {
                case 0x18 -> 5;
                case 0xfa, 0xfb -> 4;
                case 0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26,
                     0x2a, 0x2b, 0x2c, 0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75,
                     0x76, 0x77, 0x78, 0xfc, 0xfd -> 3;
                case 0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f,
                     0x20, 0x22, 0x23, 0x29, 0xfe, 0xff -> 2;
                default -> {
                    if ((opcode >= 0x2d && opcode <= 0x3d) || (opcode >= 0x44 && opcode <= 0x6d)
                            || (opcode >= 0x90 && opcode <= 0xaf) || (opcode >= 0xd0 && opcode <= 0xe2)) {
                        yield 2;
                    }
                    if ((opcode >= 0x01 && opcode <= 0x28) || (opcode >= 0x7b && opcode <= 0x8f)
                            || (opcode >= 0xb0 && opcode <= 0xcf)) {
                        yield 1;
                    }
                    throw new IOException("未知 DEX 指令");
                }
            };
        }

        private int findString(String marker) {
            int low = 0;
            int high = mData.getInt(56) - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int comparison = compareAscii(mid, marker);
                if (comparison == 0) {
                    return mid;
                }
                if (comparison < 0) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return -1;
        }

        private int compareAscii(int index, String marker) {
            int at = stringStart(index);
            for (int i = 0; i < marker.length(); i++, at++) {
                int value = Byte.toUnsignedInt(mData.get(at));
                // MUTF-8 将 U+0000 写作 C0 80，其余非 ASCII 字符均排在 ASCII 之后。
                if (value == 0xc0 && Byte.toUnsignedInt(mData.get(at + 1)) == 0x80) {
                    value = 0;
                }
                if (value != marker.charAt(i)) {
                    return value - marker.charAt(i);
                }
            }
            return mData.get(at) == 0 ? 0 : 1;
        }

        private int typeString(int index) {
            return mData.getInt(mTypes + index * 4);
        }

        private String string(int index) {
            int start = stringStart(index);
            int end = start;
            while (mData.get(end) != 0) {
                end++;
            }
            return new String(mData.array(), start, end - start, StandardCharsets.UTF_8);
        }

        private int stringStart(int index) {
            int start = mData.getInt(mStrings + index * 4);
            while (mData.get(start++) < 0) {
                // 跳过 UTF-16 长度的 ULEB128。
            }
            return start;
        }

        private int ushort(int offset) {
            return Short.toUnsignedInt(mData.getShort(offset));
        }

        private int uleb() throws IOException {
            int value = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                int next = Byte.toUnsignedInt(mData.get());
                if (shift == 28 && next > 7) {
                    break;
                }
                value |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("DEX ULEB128 无效");
        }
    }
}
