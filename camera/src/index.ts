import {
  Plugins,
  PluginImplementations,
  registerPlugin,
} from '@capacitor/core';

import {
  CameraPlugin,
  CameraOptions,
  CameraDirection,
  CameraPhoto,
  CameraResultType,
  CameraSource,
} from './definitions';
import { CameraWeb } from './web';

const implementations: PluginImplementations<CameraPlugin> = {
  android: Plugins.Camera,
  ios: Plugins.Camera,
  web: new CameraWeb(),
};

const Camera = registerPlugin('Camera', implementations).getImplementation();

export {
  Camera,
  CameraOptions,
  CameraDirection,
  CameraPhoto,
  CameraResultType,
  CameraSource,
};
