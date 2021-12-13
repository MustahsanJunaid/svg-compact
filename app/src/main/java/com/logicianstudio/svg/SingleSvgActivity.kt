package com.logicianstudio.svg

import android.os.Bundle
import com.android.kit.svg.SvgCompact
import com.android.kit.ui.activity.KitActivity
import com.logicianstudio.svg.databinding.ActivitySingleSvgBinding

class SingleSvgActivity : KitActivity<ActivitySingleSvgBinding>() {

    override fun onCreateBinding() = ActivitySingleSvgBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SvgCompact.loadAsset(assets, "1.svg").into(binding.imageView)
    }
}