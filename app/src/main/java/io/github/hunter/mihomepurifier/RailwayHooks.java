package io.github.hunter.mihomepurifier;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.security.NetworkSecurityPolicy;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;

import static io.github.hunter.mihomepurifier.MiHomeModule.TAG;
import static io.github.hunter.mihomepurifier.MiHomeModule.requireSignature;
import static io.github.hunter.mihomepurifier.MiHomeModule.unhookInstalled;

final class RailwayHooks {
    static final String PACKAGE = "com.MobileTicket";

    private static void hideHomeBanner(XposedInterface module, ClassLoader loader) {
        HookHandle cacheHook = null;
        HookHandle updateHook = null;
        HookHandle scrollHook = null;
        HookHandle layoutHook = null;
        boolean installed = false;
        try {
            Class<?> operator = loader.loadClass("com.MobileTicket.ui.operator.CarouseAdOperator");
            Class<?> ids = loader.loadClass("com.MobileTicket.R$id");
            int bannerId = ids.getField("home_ticket_item_ad").getInt(null);
            int stationId = ids.getField("home_select_station").getInt(null);
            int titleId = ids.getField("home_title").getInt(null);
            Class<?> unitClass = loader.loadClass("kotlin.Unit");
            Object unit = unitClass.getField("INSTANCE").get(null);
            Method pause = requireSignature(operator.getDeclaredMethod("pauseHandlerScroll"), void.class, false);
            Method getCache = requireSignature(operator.getDeclaredMethod("getCarouseAdData"), unitClass, false);
            Method update = requireSignature(operator.getDeclaredMethod("updateAd", boolean.class), void.class, false);
            Method scroll = requireSignature(operator.getDeclaredMethod("restartHandlerScroll"), void.class, false);
            Constructor<?> constructor = operator.getDeclaredConstructor(View.class,
                    loader.loadClass("androidx.fragment.app.FragmentActivity"),
                    loader.loadClass("com.MobileTicket.ui.fragment.HomePageFragment"));
            Set<Object> hidden = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
            cacheHook = module.hook(getCache).intercept(chain ->
                    hidden.contains(chain.getThisObject()) ? unit : chain.proceed());
            updateHook = module.hook(update).intercept(chain ->
                    hidden.contains(chain.getThisObject()) ? null : chain.proceed());
            scrollHook = module.hook(scroll).intercept(chain ->
                    hidden.contains(chain.getThisObject()) ? null : chain.proceed());
            layoutHook = module.hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                View root = (View) chain.getArg(0);
                View banner = root.findViewById(bannerId);
                View station = root.findViewById(stationId);
                View title = root.findViewById(titleId);
                if (banner != null && station != null
                        && station.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    pause.invoke(chain.getThisObject());
                    banner.setVisibility(View.GONE);
                    hidden.add(chain.getThisObject());
                    // 原查询面板以负间距覆盖广告，移除广告后改为避让标题栏和状态栏。
                    Runnable align = () -> {
                        WindowInsets insets = root.getRootWindowInsets();
                        int statusBar = insets == null ? 0 : Build.VERSION.SDK_INT >= 30
                                ? insets.getInsets(WindowInsets.Type.statusBars()).top : insets.getSystemWindowInsetTop();
                        int top = Math.max(title == null || title.getVisibility() == View.GONE ? 0 : title.getBottom(),
                                statusBar);
                        if (station.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params
                                && params.topMargin != top) {
                            params.topMargin = top;
                            station.setLayoutParams(params);
                        }
                    };
                    station.post(align);
                    if (title != null) {
                        title.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
                    }
                    module.log(Log.INFO, TAG, "12306 首页轮播已隐藏，数据更新及自动滚动已停止");
                } else {
                    module.log(Log.WARN, TAG, "12306 首页轮播布局不匹配");
                }
                return result;
            });
            installed = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(Log.ERROR, TAG, "12306 首页轮播规则不兼容", e);
        } finally {
            if (!installed) {
                unhookInstalled(module, layoutHook, scrollHook, updateHook, cacheHook);
            }
        }
    }

    static void install(XposedInterface module, ClassLoader loader) {
        redirectAdRequests(module, loader);
        blockOrderPageAd(module, loader);
        hideHomeBanner(module, loader);
        // 业务入口在 API 102 的包就绪阶段可见，各项独立检查签名。
        String[][] targets = {
                {"com.MobileTicket.ads.bean.BeanAds", "haveSplashAd"},
                {"com.MobileTicket.utils.HomePageUtils$Companion", "getCAN_SHOW_SPLASH"},
                {"com.MobileTicket.utils.AdUtils", "splashNeedShowAgain"}
        };
        for (String[] target : targets) {
            try {
                Method method = loader.loadClass(target[0]).getDeclaredMethod(target[1]);
                if (method.getReturnType() != boolean.class || Modifier.isAbstract(method.getModifiers())) {
                    throw new IllegalStateException("开屏判断返回类型已变化");
                }
                AtomicBoolean reported = new AtomicBoolean();
                module.hook(method).intercept(chain -> {
                    if (reported.compareAndSet(false, true)) {
                        module.log(Log.INFO, TAG, "12306 开屏判断已关闭：" + target[1]);
                    }
                    return false;
                });
                module.log(Log.INFO, TAG, "12306 规则已注册：" + target[1]);
            } catch (ReflectiveOperationException | RuntimeException e) {
                module.log(Log.ERROR, TAG, "12306 规则不兼容：" + target[1], e);
            }
        }
    }

    private static void blockOrderPageAd(XposedInterface module, ClassLoader loader) {
        try {
            Class<?> event = loader.loadClass("com.alipay.mobile.h5container.api.H5Event");
            Class<?> bridge = loader.loadClass("com.alipay.mobile.h5container.api.H5BridgeContext");
            Class<?> json = loader.loadClass("com.alibaba.fastjson.JSONObject");
            Method action = event.getMethod("getAction");
            Method param = event.getMethod("getParam");
            Method getObject = json.getMethod("getJSONObject", String.class);
            Method getString = json.getMethod("getString", String.class);
            Method parse = loader.loadClass("com.alibaba.fastjson.JSON").getMethod("parseObject", String.class);
            Method reply = bridge.getMethod("sendBridgeResult", json);
            Class<?> plugin = loader.loadClass("com.MobileTicket.common.plugins.H5AdExposurePlugin");
            Method handleEvent = requireSignature(plugin.getDeclaredMethod("handleEvent", event, bridge),
                    boolean.class, false);
            module.hook(handleEvent).intercept(chain -> {
                Object request = chain.getArg(0);
                if (!"exposureH5Request".equals(action.invoke(request))) return chain.proceed();
                Object options = param.invoke(request);
                Object params = options == null ? null : getObject.invoke(options, "params");
                if (params == null || !"0074".equals(getString.invoke(params, "placementNo"))
                        || !"POST".equalsIgnoreCase((String) getString.invoke(options, "type"))) {
                    return chain.proceed();
                }
                // 复用 H5 广告桥协议，让页面正常结束请求并走无素材分支。
                Object empty = parse.invoke(null, "{\"result\":\"1\",\"message\":\"200\",\"data\":{\"materialsList\":[]}}");
                reply.invoke(chain.getArg(1), empty);
                module.log(Log.INFO, TAG, "12306 订单页广告 0074 已返回空素材");
                return true;
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(Log.ERROR, TAG, "12306 订单页广告规则不兼容", e);
        }
    }

    private static void redirectAdRequests(XposedInterface module, ClassLoader loader) {
        try {
            Method url = requireSignature(loader.loadClass("com.MobileTicket.ads.AppHelp")
                    .getDeclaredMethod("getAdsUrl01", Context.class), String.class, true);
            AtomicBoolean reported = new AtomicBoolean();
            module.hook(url).intercept(chain -> {
                String original = (String) chain.proceed();
                if (original == null) {
                    return null;
                }
                Uri endpoint = Uri.parse(original);
                // 回环地址只用于确认会被系统拒绝的明文请求，避免实际连接本机。
                if (!"ad.12306.cn".equalsIgnoreCase(endpoint.getHost())
                        || NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1")) {
                    return original;
                }
                if (reported.compareAndSet(false, true)) {
                    module.log(Log.INFO, TAG, "12306 广告服务地址已替换为本机地址");
                }
                return "http://127.0.0.1/";
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(Log.ERROR, TAG, "12306 广告地址规则不兼容", e);
        }
    }
}
