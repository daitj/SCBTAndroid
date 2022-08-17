# SCBTAndroid

Steam controller Bluetooth mode fixes for Android.

The end goal of this project is to simulate XBOX 360 controller when using Steam controller via Bluetooth mode.


## Current research
### Custom mapping 
Android games sdk might have something to help with custom mappings.

[https://developer.android.com/games/sdk/game-controller/custom-mapping](https://developer.android.com/games/sdk/game-controller/custom-mapping)
### Do we need root access?
If we cannot use custom mapping then this app will be only available to rooted users, because there is no way to report different input buttons to whole system without root. There are some unofficial workaround using Accessibility framework, but that will also be crippled Android 13 onward.

## Special thanks 
- [@JnCrMx](https://github.com/JnCrMx), for their work on [moonlight-android](https://github.com/JnCrMx/moonlight-android/tree/steam-controller) to support Steam controller in Bluetooth mode.

- [@libsdl-org](https://github.com/libsdl-org), for their work with Bluetooth communication with [Steam controller](https://github.com/libsdl-org/SDL/blob/main/android-project/app/src/main/java/org/libsdl/app/HIDDeviceBLESteamController.java). 
