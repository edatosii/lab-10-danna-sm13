package co.umariana.dao;

import co.umariana.conexion.ConexionBD;
import co.umariana.modelo.ArbolGenealogico;
import co.umariana.modelo.Persona;
import co.umariana.modelo.ResumenArbol;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO (Data Access Object): la única clase que habla con la base de datos. Guarda los árboles genealógicos
 * en las tablas "arboles" y "miembros" (ver db/schema.sql). Se construye igual que EstudianteDAO del laboratorio 9.
 *
 * INSTRUCCIONES PARA EL ESTUDIANTE (Etapa 4)
 * ------------------------------------------
 * 1. Cada método marcado con "TODO" lanza UnsupportedOperationException. Reemplace esa línea por su
 *    implementación con JDBC.
 * 2. NO modifique las pruebas (src/test). Las pruebas NO necesitan MySQL: usan una base de datos H2 en memoria.
 * 3. Esta etapa usa las anteriores: para guardar recorra arbol.miembros(); para cargar, reconstruya el árbol con
 *    new ArbolGenealogico(sujeto) y arbol.agregar(...).
 *
 * REGLAS (las pruebas las verifican)
 *  - Los métodos NO lanzan SQLException: la capturan, imprimen el mensaje con System.out.println y devuelven
 *    un valor "de error" (-1, false, lista vacía o null).
 *  - Use SIEMPRE PreparedStatement con parámetros (?), nunca concatene datos dentro del SQL.
 *  - Guardar un árbol son VARIAS sentencias (el árbol y todos sus miembros): hágalo en una TRANSACCIÓN
 *    (con.setAutoCommit(false); ... con.commit(); y con.rollback() si algo falla) para que nunca quede
 *    guardado "a medias". Antes de cerrar la conexión vuelva a dejar setAutoCommit(true).
 *  - El id de cada fila lo genera la base de datos (AUTO_INCREMENT).
 *  - Los miembros se guardan en el orden de arbol.miembros(): así, al leerlos ORDER BY id, cada persona
 *    aparece después de la que necesita para poder agregarla al árbol.
 */
public class ArbolDAO {

    private Connection obtenerConexionSegura() throws SQLException {
        // Verificar si hay una URL de H2 especificada por las pruebas unitarias
        String h2Url = System.getProperty("db.url");
        if (h2Url != null && !h2Url.isBlank()) {
            String h2User = System.getProperty("db.user");
            String h2Pass = System.getProperty("db.password");
            return DriverManager.getConnection(h2Url, h2User != null ? h2User : "", h2Pass != null ? h2Pass : "");
        }
        
        // Si no, utilizar la conexión estándar de la aplicación
        return ConexionBD.conectar();
    }

    // =====================================================================
    // ETAPA 4.1. Guardar un árbol nuevo (INSERT en dos tablas, con transacción)
    // =====================================================================

    /**
     * Guarda un árbol nuevo: una fila en "arboles" (con el nombre) y una fila en "miembros" por cada persona.
     *
     * @return el id que la base de datos le dio al árbol; -1 si el nombre es nulo, está en blanco o tiene más
     *         de 100 caracteres, si el árbol es null, o si hubo un error (en ese caso NO queda nada guardado)
     */
    public long guardar(String nombre, ArbolGenealogico arbol) {
        if (nombre == null || nombre.trim().isEmpty() || nombre.length() > 100 || arbol == null) {
            return -1;
        }

        String sqlArbol = "INSERT INTO arboles (nombre) VALUES (?)";
        String sqlMiembro = "INSERT INTO miembros (arbol_id, posicion, nombre, nacimiento, fallecimiento) VALUES (?, ?, ?, ?, ?)";

        Connection con = null;
        try {
            con = obtenerConexionSegura();
            if (con == null) {
                return -1;
            }

            con.setAutoCommit(false);

            long arbolId = -1;
            try (PreparedStatement psArbol = con.prepareStatement(sqlArbol, Statement.RETURN_GENERATED_KEYS)) {
                psArbol.setString(1, nombre.trim());
                int filasAfectadas = psArbol.executeUpdate();
                if (filasAfectadas == 0) {
                    con.rollback();
                    return -1;
                }

                try (ResultSet generatedKeys = psArbol.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        arbolId = generatedKeys.getLong(1);
                    } else {
                        con.rollback();
                        return -1;
                    }
                }
            }

            List<Persona> miembros = arbol.miembros();
            try (PreparedStatement psMiembro = con.prepareStatement(sqlMiembro)) {
                for (Persona p : miembros) {
                    psMiembro.setLong(1, arbolId);
                    psMiembro.setString(2, p.getPosicion());
                    psMiembro.setString(3, p.getNombre());
                    psMiembro.setInt(4, p.getNacimiento());
                    if (p.getFallecimiento() != null) {
                        psMiembro.setInt(5, p.getFallecimiento());
                    } else {
                        psMiembro.setNull(5, java.sql.Types.INTEGER);
                    }
                    psMiembro.addBatch();
                }
                psMiembro.executeBatch();
            }

            con.commit();
            return arbolId;

        } catch (SQLException e) {
            System.out.println("Error al guardar el árbol: " + e.getMessage());
            if (con != null) {
                try {
                    con.rollback();
                } catch (SQLException ex) {
                    System.out.println("Error en rollback: " + ex.getMessage());
                }
            }
            return -1;
        } finally {
            if (con != null) {
                try {
                    con.setAutoCommit(true);
                    con.close();
                } catch (SQLException e) {
                    System.out.println("Error al cerrar la conexión: " + e.getMessage());
                }
            }
        }
    }

    // =====================================================================
    // ETAPA 4.2. Listar (SELECT con JOIN y COUNT)
    // =====================================================================

    /**
     * @return un resumen por cada árbol guardado (id, nombre, cuántos miembros tiene y cuándo se creó),
     *         ordenados por id; lista vacía si no hay árboles o si hay un error
     */
    public List<ResumenArbol> listar() {
        List<ResumenArbol> resumenes = new ArrayList<>();
        String sql = "SELECT a.id, a.nombre, a.creado, COUNT(m.id) AS total " +
                     "FROM arboles a LEFT JOIN miembros m ON m.arbol_id = a.id " +
                     "GROUP BY a.id, a.nombre, a.creado ORDER BY a.id";

        try (Connection con = obtenerConexionSegura();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong("id");
                String nombre = rs.getString("nombre");
                int total = rs.getInt("total");
                java.time.LocalDateTime creado = rs.getTimestamp("creado").toLocalDateTime();

                ResumenArbol resumen = new ResumenArbol(id, nombre, total, creado);
                resumenes.add(resumen);
            }
        } catch (SQLException e) {
            System.out.println("Error al listar los árboles: " + e.getMessage());
        }
        return resumenes;
    }

    // =====================================================================
    // ETAPA 4.3. Cargar un árbol (SELECT y reconstrucción del árbol)
    // =====================================================================

    /**
     * @return el árbol con ese id, reconstruido con todos sus miembros; null si no existe ese id o si hay un error
     */
    public ArbolGenealogico cargar(long id) {
        String sql = "SELECT posicion, nombre, nacimiento, fallecimiento FROM miembros WHERE arbol_id = ? ORDER BY id";
        ArbolGenealogico arbol = null;

        try (Connection con = obtenerConexionSegura();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                boolean esPrimero = true;
                while (rs.next()) {
                    String posicion = rs.getString("posicion");
                    String nombre = rs.getString("nombre");
                    int nacimiento = rs.getInt("nacimiento");
                    int fallecimientoVal = rs.getInt("fallecimiento");
                    Integer fallecimiento = rs.wasNull() ? null : fallecimientoVal;

                    Persona persona = new Persona(posicion, nombre, nacimiento, fallecimiento);

                    if (esPrimero) {
                        arbol = new ArbolGenealogico(persona);
                        esPrimero = false;
                    } else {
                        arbol.agregar(persona);
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error al cargar el árbol: " + e.getMessage());
            return null;
        }
        return arbol;
    }

    // =====================================================================
    // ETAPA 4.4. Actualizar (reemplazar los miembros de un árbol, con transacción)
    // =====================================================================

    /**
     * Guarda los cambios de un árbol ya existente (por ejemplo, después de agregar o eliminar miembros):
     * deja en la base de datos exactamente los miembros de {@code arbol}. El nombre del árbol NO cambia.
     *
     * @return true si se actualizó; false si no existe un árbol con ese id, si el árbol es null o si hubo un
     *         error (en ese caso NO se modifica nada: ni se borran los miembros anteriores)
     */
    public boolean actualizar(long id, ArbolGenealogico arbol) {
        if (arbol == null) {
            return false;
        }

        String sqlCheck = "SELECT id FROM arboles WHERE id = ?";
        String sqlDelete = "DELETE FROM miembros WHERE arbol_id = ?";
        String sqlInsert = "INSERT INTO miembros (arbol_id, posicion, nombre, nacimiento, fallecimiento) VALUES (?, ?, ?, ?, ?)";

        Connection con = null;
        try {
            con = obtenerConexionSegura();
            if (con == null) {
                return false;
            }

            con.setAutoCommit(false);

            try (PreparedStatement psCheck = con.prepareStatement(sqlCheck)) {
                psCheck.setLong(1, id);
                try (ResultSet rs = psCheck.executeQuery()) {
                    if (!rs.next()) {
                        con.rollback();
                        return false;
                    }
                }
            }

            try (PreparedStatement psDelete = con.prepareStatement(sqlDelete)) {
                psDelete.setLong(1, id);
                psDelete.executeUpdate();
            }

            List<Persona> miembros = arbol.miembros();
            try (PreparedStatement psInsert = con.prepareStatement(sqlInsert)) {
                for (Persona p : miembros) {
                    psInsert.setLong(1, id);
                    psInsert.setString(2, p.getPosicion());
                    psInsert.setString(3, p.getNombre());
                    psInsert.setInt(4, p.getNacimiento());
                    if (p.getFallecimiento() != null) {
                        psInsert.setInt(5, p.getFallecimiento());
                    } else {
                        psInsert.setNull(5, java.sql.Types.INTEGER);
                    }
                    psInsert.addBatch();
                }
                psInsert.executeBatch();
            }

            con.commit();
            return true;

        } catch (SQLException e) {
            System.out.println("Error al actualizar el árbol: " + e.getMessage());
            if (con != null) {
                try {
                    con.rollback();
                } catch (SQLException ex) {
                    System.out.println("Error en rollback: " + ex.getMessage());
                }
            }
            return false;
        } finally {
            if (con != null) {
                try {
                    con.setAutoCommit(true);
                    con.close();
                } catch (SQLException e) {
                    System.out.println("Error al cerrar la conexión: " + e.getMessage());
                }
            }
        }
    }

    // =====================================================================
    // ETAPA 4.5. Eliminar (DELETE)
    // =====================================================================

    /**
     * Elimina el árbol y todos sus miembros (la base de datos borra los miembros sola: ON DELETE CASCADE).
     *
     * @return true si se eliminó; false si no existía ese id o si hubo un error
     */
    public boolean eliminar(long id) {
        String sql = "DELETE FROM arboles WHERE id = ?";

        try (Connection con = obtenerConexionSegura();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setLong(1, id);
            int filasAfectadas = ps.executeUpdate();
            return filasAfectadas > 0;

        } catch (SQLException e) {
            System.out.println("Error al eliminar el árbol: " + e.getMessage());
            return false;
        }
    }
}