package io.github.hunter.mihomepurifier;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hunter.mihomepurifier.MiHomeDex.Dex.Body;
import io.github.hunter.mihomepurifier.MiHomeDex.Dex.Ref;

final class MaimaiDex {
    private static final String HOST_PREFIX = "Lcom/taou/";
    private static final String MAIN = "Lcom/taou/maimai/MainActivity;";
    static final String FRAGMENT = "Landroidx/fragment/app/FragmentActivity;";
    static final String GROUP = "Landroid/view/ViewGroup;";
    static final String FLOW_KEY = "flow_type";
    static final String HOT_FLOW = "hot_ad";
    private static final String COLD_BG = "bridge_manager_splash";
    private static final int CACHE_SCHEMA = 1;
    private static final Set<String> MARKERS = Set.of(FLOW_KEY, HOT_FLOW, COLD_BG);
    private static final Ref DECOR = new Ref("Landroid/view/Window;", "getDecorView",
            "Landroid/view/View;", List.of());
    private static final Ref EXTRA = new Ref("Landroid/content/Intent;", "getStringExtra",
            "Ljava/lang/String;", List.of("Ljava/lang/String;"));
    private static final Ref ADD_VIEW = new Ref(GROUP, "addView", "V", List.of("Landroid/view/View;"));
    private static final Ref CANCEL_TIMER = new Ref("Landroid/os/Handler;", "removeCallbacks",
            "V", List.of("Ljava/lang/Runnable;"));

    record Hot(String bridge, Ref show, Ref close) {}
    record Targets(Ref cold, Hot hot) {}
    private record Node(int flags, Body body) {}

    static Targets findCached(String apk, String[] splits, Path directory) throws IOException {
        byte[] identity = MiHomeDex.identity(apk, splits);
        Path cache = directory.resolve("ad-hunter-maimai-targets");
        try {
            if (Files.size(cache) <= 4096) {
                try (var input = new DataInputStream(Files.newInputStream(cache))) {
                    int schema = input.readInt();
                    byte[] savedIdentity = new byte[identity.length];
                    input.readFully(savedIdentity);
                    if (schema == CACHE_SCHEMA && MessageDigest.isEqual(identity, savedIdentity)) {
                        Ref cold = input.readBoolean() ? readRef(input) : null;
                        Hot hot = input.readBoolean()
                                ? new Hot(input.readUTF(), readRef(input), readRef(input)) : null;
                        if (input.read() == -1) {
                            return new Targets(cold, hot);
                        }
                    }
                }
            }
        } catch (IOException | SecurityException ignored) {
            // 缓存缺失或损坏时重新扫描。
        }
        Targets targets = find(apk, splits);
        try {
            Path temporary = Files.createTempFile(directory, "ad-hunter-maimai-", ".tmp");
            try {
                try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
                    output.writeInt(CACHE_SCHEMA);
                    output.write(identity);
                    output.writeBoolean(targets.cold != null);
                    if (targets.cold != null) {
                        writeRef(output, targets.cold);
                    }
                    output.writeBoolean(targets.hot != null);
                    if (targets.hot != null) {
                        output.writeUTF(targets.hot.bridge);
                        writeRef(output, targets.hot.show);
                        writeRef(output, targets.hot.close);
                    }
                }
                Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | SecurityException ignored) {
            // 本次仍可使用已解析的目标。
        }
        return targets;
    }

    private static Ref readRef(DataInputStream input) throws IOException {
        String owner = input.readUTF();
        String name = input.readUTF();
        int count = input.readUnsignedByte();
        if (count > 2) {
            throw new IOException("脉脉缓存方法签名无效");
        }
        var params = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            params.add(input.readUTF());
        }
        return new Ref(owner, name, "V", List.copyOf(params));
    }

    private static void writeRef(DataOutputStream output, Ref ref) throws IOException {
        output.writeUTF(ref.owner());
        output.writeUTF(ref.name());
        output.writeByte(ref.parameters().size());
        for (String param : ref.parameters()) {
            output.writeUTF(param);
        }
    }

    static Targets find(String apk, String[] splits) throws IOException {
        Map<Ref, Node> index = new HashMap<>();
        MiHomeDex.DexVisitor scan = dex -> dex.visitMethods(HOST_PREFIX, (flags, method, code) -> {
            if (!"V".equals(method.result()) || Modifier.isStatic(flags)
                    || (flags & 0x1000) != 0 || method.name().startsWith("<")) {
                return;
            }
            var params = method.parameters();
            // 只读可能出现在两条调用链上的方法体。
            if (params.isEmpty() || params.equals(List.of(GROUP))
                    || params.equals(List.of("Landroid/os/Bundle;"))
                    || (params.size() >= 2 && FRAGMENT.equals(params.get(0)))) {
                Body b = dex.body(code, MARKERS);
                if (index.putIfAbsent(method, new Node(flags, b)) != null) {
                    throw new IOException("DEX 重复方法定义");
                }
            }
        });
        MiHomeDex.forEachDex(apk, scan);
        if (splits != null) {
            for (String split : splits) {
                MiHomeDex.forEachDex(split, scan);
            }
        }
        return new Targets(findCold(index), findHot(index));
    }

    private static Ref findCold(Map<Ref, Node> index) {
        Node activity = index.get(new Ref(MAIN, "onCreate", "V", List.of("Landroid/os/Bundle;")));
        if (activity == null) {
            return null;
        }
        Ref match = null;
        for (Ref start : activity.body.calls()) {
            Node parent = index.get(start);
            if (parent == null || start.parameters().size() < 2
                    || !FRAGMENT.equals(start.parameters().get(0))) {
                continue;
            }
            for (Ref target : parent.body.calls()) {
                Node cold = index.get(target);
                if (cold == null || !Modifier.isPrivate(cold.flags)
                        || !target.owner().equals(start.owner())
                        || !target.parameters().equals(start.parameters().subList(0, 2))
                        || !cold.body.strings().contains(COLD_BG) || !cold.body.calls().contains(DECOR)) {
                    continue;
                }
                boolean mountsSplash = cold.body.calls().stream().anyMatch(show ->
                        isShow(show, index) && constructsSplash(cold.body, show.owner()));
                if (mountsSplash) {
                    if (match != null && !match.equals(target)) {
                        return null;
                    }
                    match = target;
                }
            }
        }
        return match;
    }

    private static Hot findHot(Map<Ref, Node> index) {
        Hot match = null;
        for (var entry : index.entrySet()) {
            Ref start = entry.getKey();
            Body bridge = entry.getValue().body;
            if (!start.name().equals("onStart") || !start.parameters().isEmpty()
                    || !bridge.strings().containsAll(Set.of(FLOW_KEY, HOT_FLOW))
                    || !bridge.calls().contains(EXTRA)) {
                continue;
            }
            for (Ref show : bridge.calls()) {
                if (!isShow(show, index) || !constructsSplash(bridge, show.owner())) {
                    continue;
                }
                for (Ref branch : index.get(show).body.calls()) {
                    if (!isLocalPrivateVoid(branch, show, index)) {
                        continue;
                    }
                    for (Ref close : index.get(branch).body.calls()) {
                        if (!isLocalPrivateVoid(close, show, index)) {
                            continue;
                        }
                        Body closeBody = index.get(close).body;
                        if (!closeBody.calls().contains(CANCEL_TIMER)
                                || closeBody.interfaceCalls().stream().noneMatch(callback ->
                                "V".equals(callback.result()) && callback.parameters().size() == 3
                                        && callback.parameters().stream().allMatch(p ->
                                        p.startsWith(show.owner().substring(0, show.owner().length() - 1) + "$"))
                                        && bridge.calls().stream().anyMatch(setter ->
                                        setter.owner().equals(show.owner()) && "V".equals(setter.result())
                                                && setter.parameters().equals(List.of(callback.owner()))))) {
                            continue;
                        }
                        Hot candidate = new Hot(start.owner(), show, close);
                        if (match != null && !match.equals(candidate)) {
                            return null;
                        }
                        match = candidate;
                    }
                }
            }
        }
        return match;
    }

    private static boolean isShow(Ref method, Map<Ref, Node> index) {
        Node node = index.get(method);
        return node != null && Modifier.isPublic(node.flags) && method.parameters().equals(List.of(GROUP))
                && node.body.calls().contains(ADD_VIEW);
    }

    private static boolean constructsSplash(Body body, String owner) {
        return body.calls().contains(
                new Ref(owner, "<init>", "V", List.of("Landroid/content/Context;", "Z")));
    }

    private static boolean isLocalPrivateVoid(Ref method, Ref show, Map<Ref, Node> index) {
        Node node = index.get(method);
        return node != null && method.owner().equals(show.owner()) && method.parameters().isEmpty()
                && Modifier.isPrivate(node.flags);
    }

    static String className(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }
}
