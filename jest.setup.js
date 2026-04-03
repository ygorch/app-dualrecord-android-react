
jest.mock('react-native-reanimated', () => {
  const React = require('react');
  const View = require('react-native').View;

  const AnimatedView = React.forwardRef((props, ref) => <View {...props} ref={ref} />);

  return {
    __esModule: true,
    default: {
      View: AnimatedView,
      call: () => {},
    },
    useAnimatedStyle: () => ({}),
    withTiming: (val) => val,
  };
});

jest.mock('./specs/NativeDualCameraEngine', () => {
  return {
    getAvailablePhysicalLenses: jest.fn(() => Promise.resolve([{ id: '0', focalLength: 1.8, label: 'Wide' }])),
    startRecording: jest.fn(() => Promise.resolve(true)),
    stopRecording: jest.fn(() => Promise.resolve(true)),
  };
});

jest.mock('./specs/DualCameraViewNativeComponent', () => {
  const { View } = require('react-native');
  return View;
});
