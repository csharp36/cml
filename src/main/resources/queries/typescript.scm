;; TypeScript tree-sitter queries for symbol extraction
;; Capture names: @name, @node, @path, @interface, @visibility, @parameters

;; ============================================================
;; Import statements
;; ============================================================
(import_statement
  source: (string (string_fragment) @path)
) @node

;; ============================================================
;; Interface declarations (exported or not)
;; ============================================================
(interface_declaration
  name: (type_identifier) @name
) @node

;; ============================================================
;; Class declarations with optional implements clause
;; ============================================================
(class_declaration
  name: (type_identifier) @name
  (class_heritage
    (implements_clause (type_identifier) @interface))?
) @node

;; ============================================================
;; Function declarations
;; ============================================================
(function_declaration
  name: (identifier) @name
  parameters: (formal_parameters) @parameters
) @node

;; ============================================================
;; Method definitions (inside class bodies)
;; ============================================================
(method_definition
  name: (property_identifier) @name
  parameters: (formal_parameters) @parameters
) @node
