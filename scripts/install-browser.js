import { runShellCmd } from './utils.js';
import os from 'os';

const platform = os.platform();
const browser = process.argv[2];

function installOnWindows(browser) {
  runShellCmd('Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString(\'https://community.chocolatey.org/install.ps1\'))');
  if (browser === 'chrome') runShellCmd('choco install googlechrome -y --ignore-checksums');
  else if (browser === 'firefox') runShellCmd('choco install firefox -y --ignore-checksums');
  else if (browser === 'edge') runShellCmd('choco install microsoft-edge -y --ignore-checksums');
  else if (browser === 'opera') runShellCmd('choco install opera -y --ignore-checksums');
  else if (browser === 'brave') runShellCmd('choco install brave -y --ignore-checksums');
}

function installOnUbuntu(browser) {
  runShellCmd('sudo apt update && sudo apt upgrade -y && sudo apt install -y dirmngr ca-certificates software-properties-common apt-transport-https curl dbus dbus-x11 gsettings-desktop-schemas upower xvfb gnupg wget');
  if (browser === 'chrome') {
    runShellCmd('wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | sudo apt-key add - && echo "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main" | sudo tee /etc/apt/sources.list.d/google-chrome.list && sudo apt update && sudo apt install google-chrome-stable -y');
  } else if (browser === 'firefox') {
    runShellCmd('sudo snap remove --purge firefox || true && sudo apt remove firefox -y || true && sudo add-apt-repository ppa:mozillateam/ppa -y && sudo apt update && sudo apt install -f -t "o=LP-PPA-mozillateam" firefox -y && sudo apt-mark hold firefox');
  } else if (browser === 'edge') {
    runShellCmd('curl https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor > microsoft.gpg && sudo install -o root -g root -m 644 microsoft.gpg /etc/apt/trusted.gpg.d/ && sudo sh -c \'echo "deb [arch=amd64] https://packages.microsoft.com/repos/edge stable main" > /etc/apt/sources.list.d/microsoft-edge-dev.list\' && sudo rm microsoft.gpg && sudo apt update && sudo apt install microsoft-edge-stable -y');
  } else if (browser === 'opera') {
    runShellCmd('curl -fsSL https://deb.opera.com/archive.key | gpg --dearmor | sudo tee /usr/share/keyrings/opera.gpg > /dev/null && echo deb [arch=amd64 signed-by=/usr/share/keyrings/opera.gpg] https://deb.opera.com/opera-stable/ stable non-free | sudo tee /etc/apt/sources.list.d/opera.list && sudo apt update && sudo apt install opera-stable -y');
  } else if (browser === 'brave') {
    runShellCmd('sudo curl -fsSLo /usr/share/keyrings/brave-browser-archive-keyring.gpg https://brave-browser-apt-release.s3.brave.com/brave-browser-archive-keyring.gpg && echo "deb [signed-by=/usr/share/keyrings/brave-browser-archive-keyring.gpg] https://brave-browser-apt-release.s3.brave.com/ stable main"|sudo tee /etc/apt/sources.list.d/brave-browser-release.list > /dev/null && sudo apt update && sudo apt install brave-browser -y');
  }
}

function installOnMac(browser) {
  if (browser === 'chrome') runShellCmd('brew install --cask google-chrome');
  else if (browser === 'firefox') runShellCmd('brew install --cask firefox');
  else if (browser === 'edge') runShellCmd('brew install --cask microsoft-edge');
  else if (browser === 'opera') runShellCmd('brew install --cask opera');
  else if (browser === 'brave') runShellCmd('brew install --cask brave-browser');
}

if (platform === 'win32') installOnWindows(browser);
else if (platform === 'linux') installOnUbuntu(browser);
else if (platform === 'darwin') installOnMac(browser);
else console.error('Unsupported OS');
