import React from 'react';
import { SafeAreaView, StatusBar, StyleSheet } from 'react-native';
import CameraHUD from './CameraHUD';

function App(): React.JSX.Element {
  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="black" />
      <CameraHUD />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'black',
  },
});

export default App;
