package com.audiohub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiohub.ui.theme.LocalUiTokens
import com.audiohub.ui.theme.NavStyle

/** One bottom tab. */
class NavTab(val route: String, val label: String, val icon: ImageVector)

/** Mini player (when something plays) + the tab bar, drawn the way the UI style wants. */
@Composable
fun AppBottomBar(
    tabs: List<NavTab>,
    currentRoute: String,
    onSelect: (NavTab) -> Unit,
    miniPlayer: (@Composable () -> Unit)?,
) {
    Column {
        miniPlayer?.invoke()
        when (LocalUiTokens.current.navStyle) {
            NavStyle.Labeled -> LabeledBar(tabs, currentRoute, onSelect)
            NavStyle.IconsDot -> IconsDotBar(tabs, currentRoute, onSelect)
            NavStyle.FloatingPill -> FloatingPillBar(tabs, currentRoute, onSelect)
        }
    }
}

@Composable
private fun LabeledBar(tabs: List<NavTab>, currentRoute: String, onSelect: (NavTab) -> Unit) {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            NavigationBarItem(
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                ),
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

/** Washi: bare icons on paper, the active one marked by a small accent dot. */
@Composable
private fun IconsDotBar(tabs: List<NavTab>, currentRoute: String, onSelect: (NavTab) -> Unit) {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().height(60.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            Column(
                Modifier.weight(1f).height(60.dp).tabClick(tab.label, selected) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.size(4.dp).background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ),
                )
            }
        }
    }
}

/** Glacier: a floating capsule; the active tab grows a tinted pill with its label. */
@Composable
private fun FloatingPillBar(tabs: List<NavTab>, currentRoute: String, onSelect: (NavTab) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    Row(
                        Modifier
                            .then(if (selected) Modifier.weight(1.6f) else Modifier.weight(1f))
                            .height(44.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                                CircleShape,
                            )
                            .tabClick(tab.label, selected) { onSelect(tab) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.tabClick(label: String, selected: Boolean, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interaction,
        indication = ripple(bounded = false, radius = 28.dp),
        role = Role.Tab,
        onClickLabel = label,
        onClick = onClick,
    ).semantics {
        contentDescription = label
        this.selected = selected
    }
}
