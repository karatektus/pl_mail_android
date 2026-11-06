package de.plmail.feature.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A unified inbox's icon (pl_mail#265), from the Font Awesome solid name the server sends to the
 * nearest Material one.
 *
 * The server's set is curated (`UnifiedInboxIcon`), so this covers all of it; a name it adds later
 * draws the inbox tray rather than nothing.
 */
internal fun inboxIcon(name: String): ImageVector =
    when (name) {
        "boxes-stacked" -> Icons.Outlined.Inventory2
        "envelope" -> Icons.Outlined.Mail
        "briefcase" -> Icons.Outlined.Work
        "building" -> Icons.Outlined.Business
        "code" -> Icons.Outlined.Code
        "graduation-cap" -> Icons.Outlined.School
        "house" -> Icons.Outlined.Home
        "user" -> Icons.Outlined.Person
        "users" -> Icons.Outlined.People
        "heart" -> Icons.Outlined.FavoriteBorder
        "star" -> Icons.Outlined.StarOutline
        "bell" -> Icons.Outlined.Notifications
        "bolt" -> Icons.Outlined.Bolt
        "flag" -> Icons.Outlined.Flag
        "folder" -> Icons.Outlined.Folder
        "cart-shopping" -> Icons.Outlined.ShoppingCart
        "piggy-bank" -> Icons.Outlined.Savings
        "plane" -> Icons.Outlined.Flight
        "globe" -> Icons.Outlined.Public
        "leaf" -> Icons.Outlined.Eco
        "music" -> Icons.Outlined.MusicNote
        "gamepad" -> Icons.Outlined.SportsEsports
        "box-archive" -> Icons.Outlined.Archive
        else -> Icons.Outlined.Inbox
    }
