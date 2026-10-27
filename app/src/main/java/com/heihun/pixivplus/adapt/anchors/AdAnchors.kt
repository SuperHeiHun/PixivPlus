package com.heihun.pixivplus.adapt.anchors

import com.heihun.pixivplus.adapt.ResolveSpec

object AdAnchors {

    const val REQUEST_LOG = "Request advertisement."
    const val OPT_OUT_PREF = "preference_key_yufulight_optput"

    const val ID_FUNNEL = "ads.funnel"
    const val ID_OPT_OUT = "ads.optout"

    const val DESC_FUNNEL = "Yufulight 广告请求入口"
    const val DESC_OPT_OUT = "宿主个性化广告 opt-out 开关"

    const val ADG_CLASS = "com.socdm.d.adgeneration.ADG"
    const val SELF_SERVE_MODEL_CLASS = "jp.pxv.android.data.advertisement.remote.dto.SelfServeAdvertisementApiModel"
    const val DETAIL_AD_ITEM_CLASS = "jp.pxv.android.feature.illustviewer.detail.IllustDetailAdvertisementSolidItem"

    val adViewSetupTargets: List<Pair<String, String>> = listOf(
        "jp.pxv.android.feature.advertisement.view.MangaGridADGView" to "setup",
        "jp.pxv.android.feature.advertisement.view.NovelNativeADGView" to "setup",
        "jp.pxv.android.feature.advertisement.view.RectangleADGAutoRotationView" to "setup",
        "jp.pxv.android.feature.advertisement.view.OverlayADGAutoRotationView" to "setup",
        "jp.pxv.android.feature.advertisement.view.RectangleAdgTamView" to "setup",
        "jp.pxv.android.feature.advertisement.view.OverlayAdgTamView" to "setup",
        "jp.pxv.android.feature.advertisement.view.RectangleAppLovinView" to "setup",
        "jp.pxv.android.feature.advertisement.view.YufulightRectangleAdView" to "setupAdvertisement",
        "jp.pxv.android.feature.advertisement.view.YufulightOverlayAdView" to "setupAdvertisement"
    )

    val adContainerClasses: List<String> = listOf(
        "jp.pxv.android.feature.advertisement.view.MangaGridAdSwitchView",
        "jp.pxv.android.feature.advertisement.view.NovelNativeAdSwitchView",
        "jp.pxv.android.feature.advertisement.view.RectangleAdSwitchView",
        "jp.pxv.android.feature.advertisement.view.GridSelfServeView",
        "jp.pxv.android.feature.advertisement.view.SelfServeRelatedWorksView",
        "jp.pxv.android.feature.advertisement.view.OverlayADGAutoRotationView",
        "jp.pxv.android.feature.advertisement.view.OverlayAdgTamView",
        "jp.pxv.android.feature.advertisement.view.OverlayAppLovinView",
        "jp.pxv.android.feature.advertisement.view.YufulightOverlayAdView"
    )

    val actionCreatorViews: List<String> = listOf(
        "jp.pxv.android.feature.advertisement.view.MangaGridAdSwitchView",
        "jp.pxv.android.feature.advertisement.view.NovelNativeAdSwitchView",
        "jp.pxv.android.feature.advertisement.view.RectangleAdSwitchView"
    )

    val specs: List<ResolveSpec> = listOf(
        ResolveSpec.MethodByLiteralSpec(
            id = ID_FUNNEL,
            description = DESC_FUNNEL,
            literal = REQUEST_LOG,
            methodName = "b",
            paramCount = 3
        ),
        ResolveSpec.MethodByLiteralSpec(
            id = ID_OPT_OUT,
            description = DESC_OPT_OUT,
            literal = OPT_OUT_PREF
        )
    )
}
