;; Java tree-sitter queries for symbol extraction
;; Capture names: @name, @node, @path, @parent, @superclass, @interface, @visibility, @parameters

;; ============================================================
;; Imports
;; ============================================================
(import_declaration) @node

;; ============================================================
;; Class declarations (with optional superclass and interfaces)
;; ============================================================
(class_declaration
  (modifiers)? @visibility
  name: (identifier) @name
  superclass: (superclass (type_identifier) @superclass)?
  interfaces: (super_interfaces (type_list (type_identifier) @interface))?
) @node

;; ============================================================
;; Interface declarations
;; ============================================================
(interface_declaration
  (modifiers)? @visibility
  name: (identifier) @name
) @node

;; ============================================================
;; Enum declarations
;; ============================================================
(enum_declaration
  (modifiers)? @visibility
  name: (identifier) @name
) @node

;; ============================================================
;; Method declarations (inside class/interface bodies)
;; ============================================================
(method_declaration
  (modifiers)? @visibility
  name: (identifier) @name
  parameters: (formal_parameters) @parameters
) @node

;; ============================================================
;; Constructor declarations
;; ============================================================
(constructor_declaration
  (modifiers)? @visibility
  name: (identifier) @name
  parameters: (formal_parameters) @parameters
) @node
