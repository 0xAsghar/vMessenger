package ir.vmessenger.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.vmessenger.R

@Composable
fun SplashRoute() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Use raster mipmaps — adaptive-icon XML (ic_launcher) is not supported by painterResource.
        Box(modifier = Modifier.size(96.dp)) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
