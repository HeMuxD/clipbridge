using System.Security.Cryptography;

namespace ClipBridge.Utils;

/// <summary>
/// DPAPI 加密封装。
///
/// Windows 自带的用户级加密：密文只能被当前 Windows 用户解开，
/// 因此即使配置文件被复制到别的机器或别的账户下也无法还原。
/// 不需要自己管密钥，比自己实现 AES+KDF 更不容易出错。
/// </summary>
internal static class ProtectedData
{
    /// <summary>
    /// 用户级保护的包装，失败时抛出明确异常。
    /// </summary>
    public static byte[] Protect(byte[] data, byte[]? entropy, DataProtectionScope scope)
        => System.Security.Cryptography.ProtectedData.Protect(data, entropy, scope);

    public static byte[] Unprotect(byte[] data, byte[]? entropy, DataProtectionScope scope)
        => System.Security.Cryptography.ProtectedData.Unprotect(data, entropy, scope);
}
