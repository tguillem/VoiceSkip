#!/bin/bash
# Export app icons from SVG sources
# Usage: ./export_icons.sh

set -e
cd "$(dirname "$0")"

echo "Exporting Play Store icon..."
inkscape playstore_icon.svg -w 512 -h 512 -o playstore_icon.png

echo "Exporting launcher icons..."
declare -A SIZES=(
  ["mdpi"]=108
  ["hdpi"]=162
  ["xhdpi"]=216
  ["xxhdpi"]=324
  ["xxxhdpi"]=432
)

for dir in "${!SIZES[@]}"; do
  size=${SIZES[$dir]}
  inkscape playstore_icon.svg -w $size -h $size -o ../app/src/main/res/mipmap-$dir/ic_launcher.png
done

echo "Exporting feature graphic..."
inkscape feature_graphic.svg -w 1024 -h 500 -o feature_graphic.png

echo "Exporting notification icon..."
inkscape ic_notification.svg -w 24 -h 24 -o ../app/src/main/res/drawable-mdpi/ic_notification.png
inkscape ic_notification.svg -w 36 -h 36 -o ../app/src/main/res/drawable-hdpi/ic_notification.png
inkscape ic_notification.svg -w 48 -h 48 -o ../app/src/main/res/drawable-xhdpi/ic_notification.png
inkscape ic_notification.svg -w 72 -h 72 -o ../app/src/main/res/drawable-xxhdpi/ic_notification.png
inkscape ic_notification.svg -w 96 -h 96 -o ../app/src/main/res/drawable-xxxhdpi/ic_notification.png

echo "Done!"
