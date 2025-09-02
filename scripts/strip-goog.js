const fs = require('fs');
const path = process.argv[2];

if (!path) {
  console.error('Usage: node fix-goog.js <path-to-js-file>');
  process.exit(1);
}

let content = fs.readFileSync(path, 'utf8');

// Count matches before replacing
const matches = content.match(/goog=goog\|\|\{\};/g);
const matchCount = matches ? matches.length : 0;

// Replace the matches
content = content.replace(/goog=goog\|\|\{\};/g, '');

// Write the file
fs.writeFileSync(path, content);

// Report results
if (matchCount > 0) {
  console.log(`Stripped ${matchCount} \`goog=goog||{};\` statement${matchCount === 1 ? '' : 's'} from ${path}`);
} else {
  console.log(`No goog statements found in ${path}`);
}
