import { TurboModule, TurboModuleRegistry } from 'react-native';

export interface PhysicalLens {
  id: string;
  focalLength: number;
  label: string;
}

export interface Spec extends TurboModule {
  getAvailablePhysicalLenses(): Promise<PhysicalLens[]>;
  startRecording(): Promise<boolean>;
  stopRecording(): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('DualCameraEngine');
