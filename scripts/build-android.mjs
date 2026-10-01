import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const env = { ...process.env };
const release = process.argv.includes('--release');
if (release && ['ANDROID_KEYSTORE_PATH', 'ANDROID_STORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD'].some((key) => !env[key])) {
  throw new Error('发布构建需要配置 ANDROID_KEYSTORE_PATH / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD');
}
const homebrewJdk = '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home';
const homebrewSdk = '/opt/homebrew/share/android-commandlinetools';
const configuredJava = env.JAVA_HOME && spawnSync(`${env.JAVA_HOME}/bin/java`, ['-version'], { encoding: 'utf8' });
const isJava21 = configuredJava && /version "21[.\"]/.test(configuredJava.stderr);
if (!isJava21 && existsSync(homebrewJdk)) env.JAVA_HOME = homebrewJdk;
if (!env.ANDROID_HOME && existsSync(homebrewSdk)) env.ANDROID_HOME = homebrewSdk;
if (!env.ANDROID_SDK_ROOT && env.ANDROID_HOME) env.ANDROID_SDK_ROOT = env.ANDROID_HOME;
const project = fileURLToPath(new URL('../', import.meta.url));
function run(command, args, cwd = project) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
run(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'native:sync']);
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [release ? 'assembleRelease' : 'assembleDebug', '--console=plain', '--quiet'], fileURLToPath(new URL('../android/', import.meta.url)));
