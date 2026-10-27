package com.heihun.pixivplus

import com.heihun.pixivplus.entry.InitCoordinator
import com.heihun.pixivplus.entry.PackageGuard
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ModuleEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpParam: XC_LoadPackage.LoadPackageParam) {
        if (!PackageGuard.isHost(lpParam.packageName)) return
        InitCoordinator.install(lpParam)
    }
}
