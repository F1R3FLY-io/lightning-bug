import fs from 'fs';

const pathToFile = process.argv[2];

if (!pathToFile) {
  console.error('Usage: node strip-goog.js <path-to-js-file>');
  process.exit(1);
}

// Read the file content
let content = fs.readFileSync(pathToFile, 'utf8');

// Count matches before replacing
const matches = content.match(/goog=goog\|\|\{\};/g);
const matchCount = matches ? matches.length : 0;

// Replace the matches
content = content.replace(/goog=goog\|\|\{\};/g, '');

// Write the updated content back to the file
fs.writeFileSync(pathToFile, content);

// Report results
if (matchCount > 0) {
  console.log(`Stripped ${matchCount} \`goog=goog||{};\` statement${matchCount === 1 ? '' : 's'} from ${pathToFile}`);
} else {
  console.log(`No goog statements found in ${pathToFile}`);
}
