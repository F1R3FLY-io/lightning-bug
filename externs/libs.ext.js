// Externs to prevent property renaming in advanced compilation for key libraries.

// CodeMirror (@codemirror/state, @codemirror/view, @codemirror/language, etc.)
var codemirror = {};
codemirror.state = {};
codemirror.state.Annotation = function() {};
codemirror.state.Annotation.define = function() {};
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
codemirror.view = {};
codemirror.view.Decoration = function() {};
codemirror.view.Decoration.mark = function() {};
codemirror.view.ViewPlugin = function() {};
codemirror.view.ViewPlugin.define = function() {};
codemirror.view.ViewPlugin.decorations = function() {};
codemirror.view.EditorView = function() {};
codemirror.view.EditorView.prototype.destroy = function() {};
codemirror.view.EditorView.prototype.dispatch = function() {};
codemirror.view.EditorView.prototype.state = {};
codemirror.view.EditorView.prototype.viewport = {};
codemirror.view.EditorView.updateListener = {};
codemirror.view.EditorView.updateListener.of = function() {};
codemirror.view.EditorView.theme = function() {};
codemirror.view.EditorView.scrollIntoView = function() {};
codemirror.view.keymap = {};
codemirror.view.keymap.of = function() {};
codemirror.view.lineNumbers = function() {};
codemirror.language = {};
codemirror.language.bracketMatching = function() {};
codemirror.language.indentOnInput = function() {};
codemirror.language.indentUnit = {};
codemirror.language.indentUnit.of = function() {};
codemirror.language.indentService = {};
codemirror.language.indentService.of = function() {};
codemirror.autocomplete = {};
codemirror.autocomplete.closeBrackets = function() {};
codemirror.lint = {};
codemirror.lint.lintGutter = function() {};
codemirror.lint.linter = function() {};

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
var ReactDOM = {};
ReactDOM.createRoot = function() {};
ReactDOM.createRoot.prototype.render = function() {};
ReactDOM.createRoot.prototype.unmount = function() {};

// Fetch API (built-in, but extern for safety)
var fetch = function() {};
fetch.prototype.text = function() {};

// Built-in JS (timers, JSON, Object)
var setTimeout = function() {};
var clearTimeout = function() {};
var JSON = {};
JSON.parse = function() {};
JSON.stringify = function() {};
var Object = {};
Object.create = function() {};
Object.prototype = {};

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
