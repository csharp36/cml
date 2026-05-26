;; Python tree-sitter queries for symbol extraction
;; Capture names: @name, @node, @path, @superclass, @parameters

;; ============================================================
;; Import statements
;; ============================================================
;; import os
(import_statement) @node

;; from pathlib import Path
(import_from_statement) @node

;; ============================================================
;; Class definitions (with optional superclass)
;; ============================================================
(class_definition
  name: (identifier) @name
  superclasses: (argument_list (identifier) @superclass)?
) @node

;; ============================================================
;; Function definitions (top-level and nested)
;; ============================================================
(function_definition
  name: (identifier) @name
  parameters: (parameters) @parameters
) @node
