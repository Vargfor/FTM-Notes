# Resize the supplied artwork for app assets without redrawing or recolouring it.
param([string]$Source = "$PSScriptRoot/../branding/ftm-logo-original.png")
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = [System.IO.Path]::GetFullPath("$PSScriptRoot/..")
$artwork = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $Source).Path)
try {
    $side = [Math]::Min($artwork.Width, $artwork.Height)
    $sourceRect = [System.Drawing.Rectangle]::new(($artwork.Width - $side) / 2, ($artwork.Height - $side) / 2, $side, $side)
    function Save-Logo([string]$RelativePath, [int]$Size, [bool]$Round = $false) {
        $target = Join-Path $projectRoot $RelativePath
        [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($target)) | Out-Null
        $pixelFormat = if ($Round) { [System.Drawing.Imaging.PixelFormat]::Format32bppArgb } else { [System.Drawing.Imaging.PixelFormat]::Format24bppRgb }
        $bitmap = [System.Drawing.Bitmap]::new($Size, $Size, $pixelFormat)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            if ($Round) {
                $circle = [System.Drawing.Drawing2D.GraphicsPath]::new()
                try { $circle.AddEllipse(0, 0, $Size, $Size); $graphics.SetClip($circle) } finally { $circle.Dispose() }
            }
            $graphics.DrawImage($artwork, [System.Drawing.Rectangle]::new(0, 0, $Size, $Size), $sourceRect, [System.Drawing.GraphicsUnit]::Pixel)
            $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }
    Save-Logo 'shared/src/commonMain/composeResources/drawable/ftm_logo.png' 256
    Save-Logo 'androidApp/src/main/res/drawable-nodpi/ftm_launcher.png' 432
    foreach ($density in @(@('mdpi',48), @('hdpi',72), @('xhdpi',96), @('xxhdpi',144), @('xxxhdpi',192))) {
        Save-Logo "androidApp/src/main/res/mipmap-$($density[0])/ic_launcher.png" $density[1]
        Save-Logo "androidApp/src/main/res/mipmap-$($density[0])/ic_launcher_round.png" $density[1] $true
    }
    Save-Logo 'iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png' 1024
} finally { $artwork.Dispose() }
