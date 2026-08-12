package com.hazbu.xcam.hooks;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;

import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for Activity intents that launch the camera
 * Migrated to libxposed API 101.
 */
public class IntentHook {
    private final XCamModule module;

    public IntentHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera Intent Hooks");
            hookStartActivityForResult(param);
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize camera intent hooks: " + t.getMessage());
        }
    }

    private void hookStartActivityForResult(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method start1 = Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class);
            module.hook(start1).intercept(chain -> {
                Intent intent = (Intent) chain.getArgs().get(0);
                if (isCameraIntent(intent)) {
                    module.logHook("[*] Activity: Camera Intent detected: " + intent.getAction());
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Activity#startActivityForResult(Intent, int)");

            Method start2 = Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class, Bundle.class);
            module.hook(start2).intercept(chain -> {
                Intent intent = (Intent) chain.getArgs().get(0);
                if (isCameraIntent(intent)) {
                    module.logHook("[*] Activity: Camera Intent detected (w/ bundle): " + intent.getAction());
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: Activity#startActivityForResult(Intent, int, Bundle)");

        } catch (Throwable t) {
            module.logHook("[!] Failed to hook startActivityForResult: " + t.getMessage());
        }
    }

    private boolean isCameraIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (action == null) return false;

        return action.equals(MediaStore.ACTION_IMAGE_CAPTURE) ||
                action.equals(MediaStore.ACTION_IMAGE_CAPTURE_SECURE) ||
                (action.equals(Intent.ACTION_MAIN) && intent.hasCategory("android.intent.category.APP_GALLERY"));
    }
}
