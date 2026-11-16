# Third-party notices

The MusicCut source code is licensed under [Apache-2.0](LICENSE). Third-party binaries,
dependencies, trademarks, model weights, and audio assets are **not automatically covered**
by that license.

## AI model downloads (not bundled)

| Model | Source | Notes |
| --- | --- | --- |
| UVR-MDX-NET Voc FT | [UVR model repository](https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models) | UVR project requests attribution; verify rights to the specific weights before redistribution |
| Kim Vocal 2 | [UVR model repository](https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models) | Model authorship and weights terms may differ from the application code |
| HT-Demucs 6-stem ONNX | [StemSplit ONNX model card](https://huggingface.co/StemSplitio/htdemucs-6s-onnx) | Model card describes MIT licensing; credit original Demucs and ONNX conversion authors |

The application downloads optional models directly from their original hosts at the user's
request. No trained model files are committed or bundled into MusicCut's APK.

Credits:
- [Ultimate Vocal Remover (UVR)](https://github.com/Anjok07/ultimatevocalremovergui) — MIT-licensed application and model ecosystem. Please also respect model authors' separate rights.
- [Demucs](https://github.com/facebookresearch/demucs) — original HT-Demucs work by Rouard, Massa, and Défossez (ICASSP 2023).
- [StemSplit](https://huggingface.co/StemSplitio/htdemucs-6s-onnx) — ONNX model export and packaging.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) and third-party Android/Kotlin dependencies — under their respective licenses.

**MusicCut is not affiliated with Apple or the developers of these models.** Logic Pro
and individual plugin/product names are trademarks of their respective owners.

Users are responsible for processing only audio they are entitled to edit, separate,
and distribute.
