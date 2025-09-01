/** @externs */

// Externs to prevent property renaming in advanced compilation for key libraries.

// CodeMirror (@codemirror/state, @codemirror/view, @codemirror/language, etc.)
var codemirror = {};
codemirror.state = {};
codemirror.state.Annotation = function() {};
codemirror.state.Annotation.define = function() {};
codemirror.state.Annotation.of = function() {};
codemirror.state.StateField = function() {};
codemirror.state.StateField.define = function() {};
codemirror.state.ChangeSet = function() {};
codemirror.state.ChangeSet.of = function() {};
codemirror.state.ChangeSet.iterChanges = function() {};
codemirror.state.ChangeSet.empty = {};
codemirror.state.EditorSelection = function() {};
codemirror.state.EditorSelection.cursor = function() {};
codemirror.state.EditorSelection.range = function() {};
codemirror.state.EditorState = function() {};
codemirror.state.EditorState.create = function() {};
codemirror.state.EditorState.prototype.doc = {};
codemirror.state.EditorState.prototype.selection = {};
codemirror.state.EditorState.prototype.field = function() {};
codemirror.state.RangeSetBuilder = function() {};
codemirror.state.RangeSetBuilder.prototype.add = function() {};
codemirror.state.RangeSetBuilder.prototype.finish = function() {};
codemirror.state.Compartment = function() {};
codemirror.state.Compartment.prototype.of = function() {};
codemirror.state.Compartment.prototype.reconfigure = function() {};
codemirror.state.StateEffect = function() {};
codemirror.state.StateEffect.define = function() {};
codemirror.state.Text = function() {};
codemirror.state.Text.prototype.lineAt = function() {};
codemirror.state.Text.prototype.line = function() {};
codemirror.state.Text.prototype.lines = {};
codemirror.view = {};
codemirror.view.Decoration = function() {};
codemirror.view.Decoration.mark = function() {};
codemirror.view.ViewPlugin = function() {};
codemirror.view.ViewPlugin.define = function() {};
codemirror.view.ViewPlugin.decorations = function() {};
codemirror.view.ViewUpdate = function() {};
codemirror.view.ViewUpdate.prototype.transactions = {};
codemirror.view.ViewUpdate.prototype.docChanged = {};
codemirror.view.ViewUpdate.prototype.selectionSet = {};
codemirror.view.ViewUpdate.prototype.viewportChanged = {};
codemirror.view.ViewUpdate.prototype.startState = {};
codemirror.view.ViewUpdate.prototype.state = {};
codemirror.view.ViewUpdate.prototype.view = {};
codemirror.state.Transaction = function() {};
codemirror.state.Transaction.prototype.annotation = function() {};
codemirror.state.Transaction.prototype.annotations = {};
codemirror.state.Transaction.prototype.effects = {};
codemirror.state.Transaction.prototype.changes = {};
codemirror.state.Transaction.prototype.docChanged = {};
codemirror.view.EditorView = function() {};
codemirror.view.EditorView.prototype.destroy = function() {};
codemirror.view.EditorView.prototype.dispatch = function() {};
codemirror.view.EditorView.prototype.state = {};
codemirror.view.EditorView.prototype.viewport = {};
codemirror.view.EditorView.updateListener = {};
codemirror.view.EditorView.updateListener.of = function() {};
codemirror.view.EditorView.theme = function() {};
codemirror.view.EditorView.scrollIntoView = function() {};
codemirror.view.EditorView.findFromDOM = function() {};
codemirror.view.keymap = {};
codemirror.view.keymap.of = function() {};
codemirror.view.lineNumbers = function() {};
codemirror.language = {};
codemirror.language.bracketMatching = function() {};
codemirror.language.indentService = {};
codemirror.language.indentService.of = function() {};
codemirror.language.indentUnit = {};
codemirror.language.indentUnit.of = function() {};
codemirror.autocomplete = {};
codemirror.autocomplete.closeBrackets = function() {};
codemirror.commands = {};
codemirror.commands.defaultKeymap = {};
codemirror.commands.indentWithTab = {};
codemirror.commands.indentMore = function() {};
codemirror.commands.indentLess = function() {};
codemirror.lint = {};
codemirror.lint.lintGutter = function() {};
codemirror.lint.linter = function() {};
codemirror.search = {};
codemirror.search.search = function() {};
codemirror.search.searchKeymap = {};
codemirror.search.getSearchQuery = function() {};
codemirror.search.setSearchQuery = function() {};
codemirror.search.openSearchPanel = function() {};

// web-tree-sitter
var TreeSitter = {};
TreeSitter.Parser = function() {};
TreeSitter.Parser.init = function() {};
TreeSitter.Parser.prototype.setLanguage = function() {};
TreeSitter.Parser.prototype.parse = function() {};
TreeSitter.Language = {};
TreeSitter.Language.load = function() {};
TreeSitter.Query = function() {};
TreeSitter.Query.prototype.captures = function() {};
var Node = {};
Node.prototype.rootNode = {};
Node.prototype.type = {};
Node.prototype.startIndex = {};
Node.prototype.endIndex = {};
Node.prototype.descendantForIndex = function() {};
Node.prototype.edit = function() {};
Node.prototype.copy = function() {};
Node.prototype.parent = {};
Node.prototype.text = {};

// RxJS
var rxjs = {};
rxjs.ReplaySubject = function() {};
rxjs.ReplaySubject.prototype.next = function() {};
rxjs.Subject = function() {};
rxjs.Subject.prototype.next = function() {};
rxjs.Observable = function() {};
rxjs.Observable.prototype.subscribe = function() {};
rxjs.Subscription = function() {};
rxjs.Subscription.prototype.unsubscribe = function() {};

// React and react-dom/client
var React = {};
React.createElement = function() {};
React.forwardRef = function() {};
React.createRef = function() {};
React.useRef = function() {};
React.useEffect = function() {};
React.useImperativeHandle = function() {};
React.useMemo = function() {};
React.useState = function() {};
var ReactDOM = {};
ReactDOM.createRoot = function() {};
ReactDOM.createRoot.prototype.render = function() {};
ReactDOM.createRoot.prototype.unmount = function() {};

// Fetch API (built-in, but extern for safety)
var fetch = function() {};
fetch.prototype.text = function() {};
fetch.prototype.then = function() {};
fetch.prototype.catch = function() {};

// Built-in JS (timers, JSON, Object, console, etc.)
var setTimeout = function() {};
var clearTimeout = function() {};
var JSON = {};
JSON.parse = function() {};
JSON.stringify = function() {};
var Object = {};
Object.create = function() {};
Object.prototype = {};
var console = {};
console.log = function() {};
console.warn = function() {};
console.error = function() {};
console.debug = function() {};

// TextDecoder
var TextDecoder = function() {};
TextDecoder.prototype.decode = function() {};

// WebSocket
var WebSocket = function() {};
WebSocket.prototype.binaryType = {};
WebSocket.prototype.onopen = function() {};
WebSocket.prototype.onmessage = function() {};
WebSocket.prototype.onclose = function() {};
WebSocket.prototype.onerror = function() {};
WebSocket.prototype.send = function() {};
WebSocket.prototype.close = function() {};

// Promise
var Promise = function() {};
Promise.prototype.then = function() {};
Promise.prototype.catch = function() {};

// Event
var Event = function() {};
var dispatchEvent = function() {};
var addEventListener = function() {};
var removeEventListener = function() {};

// Date
var Date = function() {};
Date.now = function() {};

// ArrayBuffer
var ArrayBuffer = function() {};

// Math
var Math = {};
Math.min = function() {};
Math.max = function() {};

// String methods (for str functions)
var String = {};
String.prototype.includes = function() {};
String.prototype.indexOf = function() {};
String.prototype.lastIndexOf = function() {};
String.prototype.substring = function() {};
String.prototype.substr = function() {};
String.prototype.length = {};
String.prototype.charCodeAt = function() {};
String.prototype.split = function() {};
String.prototype.join = function() {};

// Array methods
var Array = {};
Array.prototype.length = {};
Array.prototype.push = function() {};
Array.prototype.pop = function() {};
Array.prototype.shift = function() {};
Array.prototype.unshift = function() {};
Array.prototype.slice = function() {};
Array.prototype.splice = function() {};
Array.prototype.concat = function() {};
Array.prototype.map = function() {};
Array.prototype.filter = function() {};
Array.prototype.reduce = function() {};
Array.prototype.some = function() {};
Array.prototype.every = function() {};
Array.prototype.indexOf = function() {};
Array.prototype.lastIndexOf = function() {};
Array.prototype.forEach = function() {};
Array.prototype.includes = function() {};

// RegExp
var RegExp = function() {};
RegExp.prototype.test = function() {};
RegExp.prototype.exec = function() {};

// document and window (for tests)
var document = {};
document.createElement = function() {};
document.body = {};
document.body.appendChild = function() {};
document.body.removeChild = function() {};
document.getElementById = function() {};
document.querySelector = function() {};

var window = {};
window.addEventListener = function() {};
window.removeEventListener = function() {};
window.dispatchEvent = function() {};
window.console = console;

// Karma test globals (if needed)
var karma = {};
karma.start = function() {};

// Closure externs for datascript
var datascript = {};
datascript.core = {};
datascript.core.create_conn = function() {};
datascript.core.q = function() {};
datascript.core.transact_BANG_ = function() {};
datascript.core.entity = function() {};
datascript.core.db = function() {};
datascript.core.pull = function() {};
datascript.core.empty_db = function() {};
datascript.core.reset_conn_BANG_ = function() {};

// Other potential externs
var goog = {};
goog.object = {};
goog.object.get = function() {};
goog.object.set = function() {};
goog.object.getKeys = function() {};
goog.object.getValueByKeys = function() {};
goog.object.containsKey = function() {};
goog.object.clone = function() {};
goog.object.transpose = function() {};
goog.object.clear = function() {};
goog.object.remove = function() {};
goog.object.add = function() {};
goog.object.extend = function() {};

