;; C tree-sitter queries for symbol extraction
;; Capture names: @name, @node, @path, @visibility, @parameters

;; ============================================================
;; Preprocessor includes
;; ============================================================
(preproc_include
  path: (_) @path
) @node

;; ============================================================
;; Function definitions
;; ============================================================
(function_definition
  declarator: (function_declarator
    declarator: (identifier) @name
    parameters: (parameter_list) @parameters)
) @node

;; ============================================================
;; Function definitions with storage class specifier (static)
;; ============================================================
;; Note: storage_class_specifier is handled in the engine by checking
;; the @node text for "static" keyword presence

;; ============================================================
;; Named struct specifiers (standalone struct declarations)
;; ============================================================
(struct_specifier
  name: (type_identifier) @name
) @node

;; ============================================================
;; Enum specifiers
;; ============================================================
(enum_specifier
  name: (type_identifier) @name
) @node

;; ============================================================
;; Typedefs (e.g., typedef struct { ... } Point;)
;; ============================================================
(type_definition
  declarator: (type_identifier) @name
) @node
