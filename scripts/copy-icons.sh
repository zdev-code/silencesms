#!/usr/bin/env bash

SOURCE_DIRECTORY="$1"
ICON_TO_COPY="$2"
COLOR="$3"
SIZE="$4"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

USAGE_HELPER="usage: $0 <source_directory> <icon_to_copy> <white|black> <18dp|24dp|36dp|48dp>"

if [[ -z $SOURCE_DIRECTORY ]] || [[ -z $ICON_TO_COPY ]] || [[ -z $COLOR ]] || [[ -z $SIZE ]]; then
	echo "Copy an icon from the Google's Material Design icons repository. Clone https://github.com/google/material-design-icons"
    echo $USAGE_HELPER
    exit 1
fi

if [[ $SOURCE_DIRECTORY != /* ]]; then
	SOURCE_DIRECTORY="$(pwd)/$SOURCE_DIRECTORY"
fi
cd "$REPO_ROOT"

if [[ $COLOR != "white" ]] && [[ $COLOR != "black" ]] ; then
	echo "Invalid color: can be white or black"
	echo $USAGE_HELPER
	exit 1
fi

if [[ $SIZE != "18dp" ]] && [[ $SIZE != "24dp" ]] && [[ $SIZE != "36dp" ]] && [[ $SIZE != "48dp" ]] ; then
	echo "Invalid size: can be 18dp, 24dp, 36dp or 48dp"
	echo $USAGE_HELPER
	exit 1
fi

if [[ -d $SOURCE_DIRECTORY ]] ; then
	declare -a resolutions=("mdpi" "hdpi" "xhdpi" "xxhdpi" "xxxhdpi")

	for resolution in "${resolutions[@]}"; do
		CANDIDATE=$( find "$SOURCE_DIRECTORY" -type f | grep drawable-"$resolution"/ic_"$ICON_TO_COPY"_"$COLOR"_"$SIZE".png )
		if [[ -z $CANDIDATE ]] ; then
			echo "Cannot find ic_"$ICON_TO_COPY"_"$COLOR"_"$SIZE".png for resolution $resolution"
		else
			echo "Found $CANDIDATE"
			cp -L $CANDIDATE app/src/main/res/drawable-"$resolution"/ic_"$ICON_TO_COPY"_"$COLOR"_"$SIZE".png
		fi
	done
else
	echo "$SOURCE_DIRECTORY is not a valid directory"
	echo $USAGE_HELPER
	exit 1
fi
