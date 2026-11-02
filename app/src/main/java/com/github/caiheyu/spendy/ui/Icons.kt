package com.github.caiheyu.spendy.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import com.github.caiheyu.spendy.R

private val iconIds = mapOf(
    "utensils" to R.drawable.ic_utensils,
    "subway" to R.drawable.ic_subway,
    "bag" to R.drawable.ic_bag,
    "home" to R.drawable.ic_home,
    "film" to R.drawable.ic_film,
    "phone" to R.drawable.ic_phone,
    "med" to R.drawable.ic_med,
    "book" to R.drawable.ic_book,
    "plane" to R.drawable.ic_plane,
    "money" to R.drawable.ic_money,
    "trend" to R.drawable.ic_trend,
    "star" to R.drawable.ic_star,
    "card" to R.drawable.ic_card,
    "dots" to R.drawable.ic_dots,
    "eye" to R.drawable.ic_eye,
    "eyeOff" to R.drawable.ic_eye_off,
    "search" to R.drawable.ic_search,
    "filter" to R.drawable.ic_filter,
    "info" to R.drawable.ic_info,
    "chevL" to R.drawable.ic_chev_l,
    "chevR" to R.drawable.ic_chev_r,
    "chevD" to R.drawable.ic_chev_d,
    "x" to R.drawable.ic_x,
    "trash" to R.drawable.ic_trash,
    "check" to R.drawable.ic_check,
    "backspace" to R.drawable.ic_backspace,
    "wallet" to R.drawable.ic_wallet,
    "calendar" to R.drawable.ic_calendar,
    "tag" to R.drawable.ic_tag,
    "pencil" to R.drawable.ic_pencil,
    "plus" to R.drawable.ic_plus,
    "moon" to R.drawable.ic_moon,
    "palette" to R.drawable.ic_palette,
    "download" to R.drawable.ic_download,
    "upload" to R.drawable.ic_upload,
    "db" to R.drawable.ic_db,
    "refresh" to R.drawable.ic_refresh,
    "link" to R.drawable.ic_link,
    "grid" to R.drawable.ic_grid,
    "user" to R.drawable.ic_user,
    "list" to R.drawable.ic_list,
    "chart" to R.drawable.ic_chart,
    "coffee" to R.drawable.ic_coffee,
    "gift" to R.drawable.ic_gift,
    "signal" to R.drawable.ic_signal,
    "wifi" to R.drawable.ic_wifi,
    "battery" to R.drawable.ic_battery,
    "bus" to R.drawable.ic_bus,
    "car" to R.drawable.ic_car,
    "fuel" to R.drawable.ic_fuel,
    "cart" to R.drawable.ic_cart,
    "shirt" to R.drawable.ic_shirt,
    "laptop" to R.drawable.ic_laptop,
    "paw" to R.drawable.ic_paw,
    "dumbbell" to R.drawable.ic_dumbbell,
    "scissors" to R.drawable.ic_scissors,
    "heart" to R.drawable.ic_heart,
    "users" to R.drawable.ic_users,
    "briefcase" to R.drawable.ic_briefcase,
    "award" to R.drawable.ic_award,
    "redPacket" to R.drawable.ic_red_packet,
    "receipt" to R.drawable.ic_receipt,
    "gamepad" to R.drawable.ic_gamepad,
    "music" to R.drawable.ic_music,
    "camera" to R.drawable.ic_camera,
    "ticket" to R.drawable.ic_ticket,
    "building" to R.drawable.ic_building,
    "wrench" to R.drawable.ic_wrench,
    "zap" to R.drawable.ic_zap,
    "droplet" to R.drawable.ic_droplet,
    "pill" to R.drawable.ic_pill,
    "graduation" to R.drawable.ic_graduation,
    "package" to R.drawable.ic_package,
    "percent" to R.drawable.ic_percent,
    "piggy" to R.drawable.ic_piggy,
    "baby" to R.drawable.ic_baby,
    "flower" to R.drawable.ic_flower,
    "beer" to R.drawable.ic_beer,
    "train" to R.drawable.ic_train,
    "charge" to R.drawable.ic_charge,
    "apple" to R.drawable.ic_apple,
    "soda" to R.drawable.ic_soda,
    "cookie" to R.drawable.ic_cookie,
    "store" to R.drawable.ic_store,
    "send" to R.drawable.ic_send
)
@Composable
fun SIcon(name: String, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val bitmap = androidx.compose.runtime.remember(name) {
        if (name.startsWith("data:image/") && name.length < 100_000) runCatching {
            val bytes = android.util.Base64.decode(name.substringAfter(','), android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull() else null
    }
    if (bitmap != null) androidx.compose.foundation.Image(bitmap, description, modifier)
    else Icon(painterResource(iconIds[name] ?: R.drawable.ic_dots), description, modifier, tint)
}
