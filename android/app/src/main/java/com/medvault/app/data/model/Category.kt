package com.medvault.app.data.model

import androidx.annotation.StringRes
import com.medvault.app.R

/** Fixed, code-defined categories (see SPEC.md §5); free-form organization happens via tags. */
enum class Category(@StringRes val labelRes: Int) {
    LABORBEFUND(R.string.category_laborbefund),
    ARZTBRIEF(R.string.category_arztbrief),
    BILDGEBUNG(R.string.category_bildgebung),
    MEDIKATION(R.string.category_medikation),
    IMPFUNG(R.string.category_impfung),
    SONSTIGES(R.string.category_sonstiges),
}

enum class OcrStatus {
    PENDING,
    DONE,
    FAILED,
    NOT_APPLICABLE,
}
