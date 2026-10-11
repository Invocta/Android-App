# Invocta development kit

This folder contains the debug APK and the Gemma 4 E2B IT model used by Invocta. The model is kept beside the APK because it is 2.58 GB. Android Studio can keep installing updated APKs without copying the model again.

1. Connect an Android device with USB debugging enabled and at least 3 GB of free storage. The model is recommended for devices with 8 GB RAM or more.
2. Run `./install.sh` from this folder. Set `ANDROID_SERIAL` first if multiple devices are connected.
3. Open Invocta → Settings → On-device AI → **Use Gemma**.

The installer preserves app data and skips the model transfer if the device already has the verified file. Rebuild this kit from the repository with `scripts/prepare-dev-kit.sh`.

Model: [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), revision `7fa1d78473894f7e736a21d920c3aa80f950c0db`. SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`. The model card lists Apache 2.0 licensing.
