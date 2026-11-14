package io.github.hunter.mihomepurifier;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

final class MaimaiSplashRule {
    static final String PACKAGE = "com.taou.maimai";
    private static final String TAG = MiHomeModule.TAG;

    static void attach(XposedModule module, PackageReadyParam param) {
        ClassLoader loader = param.getClassLoader();
        ApplicationInfo app = param.getApplicationInfo();
        try {
            Method newApplication = Instrumentation.class.getDeclaredMethod(
                    "newApplication", ClassLoader.class, String.class, Context.class);
            var startup = new Hooker() {
                private HookHandle mHandle;

                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Context context = (Context) chain.getArg(2);
                    if (chain.getArg(0) == loader && PACKAGE.equals(context.getPackageName())) {
                        try {
                            mHandle.unhook();
                            var targets = MaimaiDex.findCached(app.sourceDir, app.splitSourceDirs,
                                    context.getCodeCacheDir().toPath());
                            installCold(module, loader, targets.cold());
                            installHot(module, loader, targets.hot());
                        } catch (IOException | RuntimeException e) {
                            module.log(Log.WARN, TAG, "脉脉 DEX 读取或初始化失败", e);
                        }
                    }
                    return chain.proceed();
                }
            };
            startup.mHandle = module.hook(newApplication).intercept(startup);
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(Log.WARN, TAG, "脉脉开屏初始化入口不兼容", e);
        }
    }

    static void installCold(XposedInterface api, ClassLoader loader, MiHomeDex.Dex.Ref target) {
        if (target == null) {
            api.log(Log.WARN, TAG, "脉脉冷启动广告调用链未唯一匹配");
            return;
        }
        try {
            if (!"V".equals(target.result()) || target.parameters().size() != 2
                    || !MaimaiDex.FRAGMENT.equals(target.parameters().get(0))) {
                throw new NoSuchMethodException("脉脉冷启动描述符不匹配");
            }
            Class<?> main = Class.forName(PACKAGE + ".MainActivity", false, loader);
            Class<?> activity = load(MaimaiDex.FRAGMENT, loader);
            Class<?> popLayers = load(target.parameters().get(1), loader);
            Method show = requireVoid(load(target.owner(), loader), target.name(), true, activity, popLayers);
            if (!Activity.class.isAssignableFrom(activity) || !activity.isAssignableFrom(main)) {
                throw new NoSuchMethodException("脉脉冷启动 Activity 类型不匹配");
            }
            // 在广告遮罩和 Handler 创建前退出，调用者继续初始化首页。
            api.hook(show).intercept(chain -> {
                if (!main.isInstance(chain.getArg(0))) {
                    return chain.proceed();
                }
                api.log(Log.INFO, TAG, "脉脉：已跳过冷启动广告及等待");
                return null;
            });
            api.log(Log.INFO, TAG, "脉脉冷启动规则已注册：" + show.getName());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            api.log(Log.WARN, TAG, "脉脉冷启动规则不兼容", e);
        }
    }

    static void installHot(XposedInterface api, ClassLoader loader, MaimaiDex.Hot target) {
        if (target == null) {
            api.log(Log.WARN, TAG, "脉脉热启动广告调用链未唯一匹配");
            return;
        }
        try {
            Class<?> bridge = load(target.bridge(), loader);
            Class<?> splash = load(target.show().owner(), loader);
            if (!Activity.class.isAssignableFrom(bridge) || !FrameLayout.class.isAssignableFrom(splash)
                    || !target.show().owner().equals(target.close().owner())
                    || !"V".equals(target.show().result()) || !"V".equals(target.close().result())
                    || !target.show().parameters().equals(java.util.List.of(MaimaiDex.GROUP))
                    || !target.close().parameters().isEmpty()) {
                throw new NoSuchMethodException("脉脉热启动组件类型不匹配");
            }
            Method show = requireVoid(splash, target.show().name(), false, ViewGroup.class);
            Method close = requireVoid(splash, target.close().name(), true);
            close.setAccessible(true);
            api.hook(show).intercept(chain -> {
                View ad = (View) chain.getThisObject();
                if (!bridge.isInstance(ad.getContext()) || ad.getParent() != null) {
                    return chain.proceed();
                }
                Intent intent = ((Activity) ad.getContext()).getIntent();
                if (intent == null || !MaimaiDex.HOT_FLOW.equals(intent.getStringExtra(MaimaiDex.FLOW_KEY))) {
                    return chain.proceed();
                }
                // 使用宿主关闭回调；同一 Activity 的隐私流程保留。
                try {
                    close.invoke(ad);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                api.log(Log.INFO, TAG, "脉脉：已跳过热启动广告及倒计时");
                return null;
            });
            api.log(Log.INFO, TAG, "脉脉热启动规则已注册：" + show.getName());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            api.log(Log.WARN, TAG, "脉脉热启动规则不兼容", e);
        }
    }

    private static Class<?> load(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        if (descriptor.length() < 3 || descriptor.charAt(0) != 'L' || !descriptor.endsWith(";")) {
            throw new ClassNotFoundException("无效的宿主类型描述符");
        }
        return Class.forName(MaimaiDex.className(descriptor), false, loader);
    }

    private static Method requireVoid(Class<?> owner, String name, boolean privateMethod,
                                      Class<?>... parameters) throws NoSuchMethodException {
        Method method = MiHomeModule.requireSignature(owner.getDeclaredMethod(name, parameters), void.class, false);
        int flags = method.getModifiers();
        if (Modifier.isAbstract(flags) || method.isSynthetic()
                || !(privateMethod ? Modifier.isPrivate(flags) : Modifier.isPublic(flags))) {
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }
        return method;
    }
}
